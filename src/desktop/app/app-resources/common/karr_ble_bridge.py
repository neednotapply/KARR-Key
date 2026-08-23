"""KARR Key desktop -- Bluetooth LE helper (Python + bleak).

Raw BLE transport (scan / connect / discover UART / subscribe / write / notify)
bridged to the JVM WindowsBleTransport by a line-delimited JSON protocol on
stdio. It knows nothing about the KARR protocol or authentication -- the JVM
WindowsBleController owns that. bleak uses WinRT on Windows (and CoreBluetooth /
BlueZ elsewhere), so this is cross-platform.

Requires: Python 3.9+ and `bleak` (`python -m pip install bleak`).

Protocol (one JSON object per line):
  JVM -> helper:  {"cmd":"startScan"} {"cmd":"stopScan"}
                  {"cmd":"connect","address":".."} {"cmd":"disconnect"}
                  {"cmd":"write","data":"<hex>"} {"cmd":"shutdown"}
  helper -> JVM:  {"event":"scan","address","name","rssi","connectable",
                   "services":[uuid],"manufacturerData":["<hex>"]}
                  {"event":"connected","address"} {"event":"uartReady"}
                  {"event":"notification","data":"<hex>"}
                  {"event":"disconnected","status":N} {"event":"error","message"}
"""

import asyncio
import json
import sys

from bleak import BleakClient, BleakScanner

UART_SERVICE = "49535343-fe7d-4ae5-8fa9-9fafd205e455"
UART_CHARACTERISTIC = "49535343-1e4d-4bd9-ba61-23c647249616"


def emit(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


class Bridge:
    def __init__(self):
        self.scanner = None
        self.client = None
        self.uart = None

    async def start_scan(self):
        await self.stop_scan()
        self.scanner = BleakScanner(detection_callback=self._on_adv)
        await self.scanner.start()

    async def stop_scan(self):
        if self.scanner is not None:
            try:
                await self.scanner.stop()
            except Exception:
                pass
            self.scanner = None

    def _on_adv(self, device, adv):
        manufacturer = []
        for company_id, data in (adv.manufacturer_data or {}).items():
            # Reconstruct the complete manufacturer record: 2-byte company id
            # (little-endian) + payload, matching CompatibleKarrDevice's parser.
            record = bytes([company_id & 0xFF, (company_id >> 8) & 0xFF]) + bytes(data)
            manufacturer.append(record.hex())
        emit({
            "event": "scan",
            "address": device.address,
            "name": adv.local_name or "",
            "rssi": adv.rssi if adv.rssi is not None else -100,
            # bleak does not surface the connectable flag uniformly; the JVM
            # classifier + user action gate connection, so report true.
            "connectable": True,
            "services": list(adv.service_uuids or []),
            "manufacturerData": manufacturer,
        })

    async def connect(self, address):
        await self.stop_scan()
        await self.disconnect()
        self.client = BleakClient(address, disconnected_callback=self._on_disconnected)
        await self.client.connect()
        emit({"event": "connected", "address": address})
        self.uart = self._find_uart()
        if self.uart is None:
            emit({"event": "error", "message": "UART characteristic not found."})
            return
        await self.client.start_notify(self.uart, self._on_notify)
        emit({"event": "uartReady"})

    def _find_uart(self):
        for service in self.client.services:
            if service.uuid.lower() == UART_SERVICE:
                for characteristic in service.characteristics:
                    if characteristic.uuid.lower() == UART_CHARACTERISTIC:
                        return characteristic
        return None

    def _on_notify(self, _characteristic, data):
        emit({"event": "notification", "data": bytes(data).hex()})

    def _on_disconnected(self, _client):
        emit({"event": "disconnected", "status": 0})

    async def write(self, hex_data):
        if self.client is not None and self.client.is_connected and self.uart is not None:
            await self.client.write_gatt_char(self.uart, bytes.fromhex(hex_data), response=False)

    async def disconnect(self):
        if self.client is not None:
            try:
                await self.client.disconnect()
            except Exception:
                pass
            self.client = None
            self.uart = None


async def main():
    bridge = Bridge()
    loop = asyncio.get_running_loop()
    while True:
        # Read stdin on a worker thread so bleak callbacks keep running on the loop.
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
            command = message.get("cmd")
            if command == "startScan":
                await bridge.start_scan()
            elif command == "stopScan":
                await bridge.stop_scan()
            elif command == "connect":
                await bridge.connect(message["address"])
            elif command == "disconnect":
                await bridge.disconnect()
            elif command == "write":
                await bridge.write(message["data"])
            elif command == "shutdown":
                await bridge.disconnect()
                break
        except Exception as error:  # noqa: BLE001 - report, keep the bridge alive
            emit({"event": "error", "message": str(error)})


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
