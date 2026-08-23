// KARR Key desktop — Windows-native BLE helper.
//
// Raw BLE transport (scan / connect / discover UART / subscribe / write / notify)
// over WinRT, bridged to the JVM WindowsBleTransport by a line-delimited JSON
// protocol on stdio. It intentionally knows nothing about the KARR protocol or
// authentication — the JVM WindowsBleController owns that.
//
// STATUS: source-backed. The JVM-side orchestration is unit-tested against a fake
// transport; this helper compiles under the .NET 8 SDK but is pending validation
// against physical Windows hardware and a KARR unit.
//
// Protocol
//   JVM -> helper (one JSON object per line):
//     {"cmd":"startScan"} {"cmd":"stopScan"} {"cmd":"connect","address":"AA:BB:.."}
//     {"cmd":"disconnect"} {"cmd":"write","data":"<hex>"} {"cmd":"shutdown"}
//   helper -> JVM:
//     {"event":"scan","address":"..","name":"..","rssi":-60,"connectable":true,
//      "services":["<uuid>"],"manufacturerData":["<hex>"]}
//     {"event":"connected","address":".."} {"event":"uartReady"}
//     {"event":"notification","data":"<hex>"} {"event":"disconnected","status":N}
//     {"event":"error","message":".."}

using System.Text.Json;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

internal static class Program
{
    // Microchip transparent UART service/characteristic used by KARR units.
    private static readonly Guid UartService = Guid.Parse("49535343-FE7D-4AE5-8FA9-9FAFD205E455");
    private static readonly Guid UartCharacteristic = Guid.Parse("49535343-1E4D-4BD9-BA61-23C647249616");

    private static readonly object WriteLock = new();
    private static BluetoothLEAdvertisementWatcher? _watcher;
    private static BluetoothLEDevice? _device;
    private static GattCharacteristic? _uart;

    private static async Task<int> Main()
    {
        string? line;
        while ((line = Console.ReadLine()) != null)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            try
            {
                using var doc = JsonDocument.Parse(line);
                var cmd = doc.RootElement.GetProperty("cmd").GetString();
                switch (cmd)
                {
                    case "startScan": StartScan(); break;
                    case "stopScan": StopScan(); break;
                    case "connect": await ConnectAsync(doc.RootElement.GetProperty("address").GetString()!); break;
                    case "disconnect": Disconnect(); break;
                    case "write": await WriteAsync(doc.RootElement.GetProperty("data").GetString()!); break;
                    case "shutdown": Disconnect(); return 0;
                }
            }
            catch (Exception ex)
            {
                EmitError(ex.Message);
            }
        }
        return 0;
    }

    private static void StartScan()
    {
        StopScan();
        var watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        watcher.Received += OnAdvertisement;
        watcher.Start();
        _watcher = watcher;
    }

    private static void StopScan()
    {
        if (_watcher == null) return;
        _watcher.Received -= OnAdvertisement;
        _watcher.Stop();
        _watcher = null;
    }

    private static void OnAdvertisement(
        BluetoothLEAdvertisementWatcher sender,
        BluetoothLEAdvertisementReceivedEventArgs args)
    {
        var services = args.Advertisement.ServiceUuids.Select(u => u.ToString()).ToList();
        var manufacturerData = args.Advertisement.ManufacturerData.Select(md =>
        {
            var data = ToBytes(md.Data);
            var complete = new byte[data.Length + 2];
            complete[0] = (byte)(md.CompanyId & 0xff);
            complete[1] = (byte)((md.CompanyId >> 8) & 0xff);
            Array.Copy(data, 0, complete, 2, data.Length);
            return ToHex(complete);
        }).ToList();

        Emit(new
        {
            @event = "scan",
            address = FormatAddress(args.BluetoothAddress),
            name = args.Advertisement.LocalName,
            rssi = args.RawSignalStrengthInDBm,
            connectable = args.IsConnectable,
            services,
            manufacturerData,
        });
    }

    private static async Task ConnectAsync(string address)
    {
        StopScan();
        Disconnect();
        var device = await BluetoothLEDevice.FromBluetoothAddressAsync(ParseAddress(address));
        if (device == null) { EmitError("Unable to open the BLE device."); return; }
        _device = device;
        device.ConnectionStatusChanged += OnConnectionStatusChanged;
        Emit(new { @event = "connected", address });

        var services = await device.GetGattServicesForUuidAsync(UartService);
        var service = services.Services.FirstOrDefault();
        if (service == null) { EmitError("UART service not found."); return; }
        var chars = await service.GetCharacteristicsForUuidAsync(UartCharacteristic);
        var characteristic = chars.Characteristics.FirstOrDefault();
        if (characteristic == null) { EmitError("UART characteristic not found."); return; }
        _uart = characteristic;
        characteristic.ValueChanged += OnValueChanged;
        await characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue.Notify);
        Emit(new { @event = "uartReady" });
    }

    private static void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            Emit(new { @event = "disconnected", status = 0 });
        }
    }

    private static void OnValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        Emit(new { @event = "notification", data = ToHex(ToBytes(args.CharacteristicValue)) });
    }

    private static async Task WriteAsync(string hex)
    {
        var characteristic = _uart;
        if (characteristic == null) { EmitError("Not connected."); return; }
        var writer = new DataWriter();
        writer.WriteBytes(FromHex(hex));
        await characteristic.WriteValueAsync(writer.DetachBuffer(), GattWriteOption.WriteWithoutResponse);
    }

    private static void Disconnect()
    {
        if (_uart != null) { _uart.ValueChanged -= OnValueChanged; _uart = null; }
        if (_device != null)
        {
            _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
            _device.Dispose();
            _device = null;
        }
    }

    private static void Emit(object payload)
    {
        var json = JsonSerializer.Serialize(payload);
        lock (WriteLock) { Console.Out.WriteLine(json); Console.Out.Flush(); }
    }

    private static void EmitError(string message) => Emit(new { @event = "error", message });

    private static byte[] ToBytes(IBuffer buffer)
    {
        var reader = DataReader.FromBuffer(buffer);
        var bytes = new byte[buffer.Length];
        reader.ReadBytes(bytes);
        return bytes;
    }

    private static string ToHex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();

    private static byte[] FromHex(string hex) => Convert.FromHexString(hex);

    private static string FormatAddress(ulong address)
    {
        var bytes = BitConverter.GetBytes(address);
        return string.Join(":", Enumerable.Range(0, 6).Reverse().Select(i => bytes[i].ToString("X2")));
    }

    private static ulong ParseAddress(string address)
    {
        var parts = address.Split(':');
        ulong value = 0;
        foreach (var part in parts) value = (value << 8) | Convert.ToByte(part, 16);
        return value;
    }
}
