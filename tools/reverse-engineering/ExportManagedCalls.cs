using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Mono.Cecil;
using Mono.Cecil.Cil;

internal static class ExportManagedCalls
{
    private static string Csv(string value)
    {
        value = value ?? string.Empty;
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }

    private static IEnumerable<TypeDefinition> Walk(TypeDefinition type)
    {
        yield return type;
        foreach (TypeDefinition nested in type.NestedTypes)
            foreach (TypeDefinition child in Walk(nested))
                yield return child;
    }

    private static string Boundary(string scope, HashSet<string> firstParty)
    {
        string withoutExtension = Path.GetFileNameWithoutExtension(scope ?? string.Empty);
        if (firstParty.Contains(withoutExtension)) return "application";
        if ((scope ?? string.Empty).StartsWith("Plugin.BLE", StringComparison.Ordinal)) return "bluetooth_library";
        if ((scope ?? string.Empty).StartsWith("SQLite", StringComparison.Ordinal)) return "database_library";
        if ((scope ?? string.Empty).StartsWith("Xamarin.Forms", StringComparison.Ordinal)) return "ui_framework";
        if ((scope ?? string.Empty).StartsWith("Xamarin.Essentials", StringComparison.Ordinal)) return "platform_services";
        if ((scope ?? string.Empty).StartsWith("Xamarin.Firebase", StringComparison.Ordinal)) return "push_messaging";
        if (scope == "System.Net.Http" || scope == "System") return "system_or_network";
        return "dependency";
    }

    private static void Main(string[] args)
    {
        if (args.Length != 3)
            throw new ArgumentException("usage: ExportManagedCalls ASSEMBLY_DIR OUTPUT_CSV ASSEMBLY_NAMES_SEMICOLON");

        string directory = args[0];
        string[] names = args[2].Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries);
        HashSet<string> firstParty = new HashSet<string>(
            names.Select(Path.GetFileNameWithoutExtension),
            StringComparer.OrdinalIgnoreCase
        );

        using (StreamWriter output = new StreamWriter(args[1], false, new UTF8Encoding(true)))
        {
            output.WriteLine("caller_assembly,caller_type,caller_method,il_offset,callee_scope,callee_type,callee_method,boundary");
            foreach (string name in names)
            {
                string path = Path.Combine(directory, name);
                using (AssemblyDefinition assembly = AssemblyDefinition.ReadAssembly(path))
                {
                    foreach (TypeDefinition type in assembly.MainModule.Types.SelectMany(Walk))
                    {
                        foreach (MethodDefinition method in type.Methods.Where(method => method.HasBody))
                        {
                            foreach (Instruction instruction in method.Body.Instructions)
                            {
                                MethodReference called = instruction.Operand as MethodReference;
                                if (called == null) continue;
                                string scope = called.DeclaringType.Scope == null
                                    ? string.Empty
                                    : called.DeclaringType.Scope.Name;
                                output.WriteLine(string.Join(",", new[] {
                                    Csv(name),
                                    Csv(type.FullName),
                                    Csv(method.Name),
                                    Csv("IL_" + instruction.Offset.ToString("X4")),
                                    Csv(scope),
                                    Csv(called.DeclaringType.FullName),
                                    Csv(called.Name),
                                    Csv(Boundary(scope, firstParty))
                                }));
                            }
                        }
                    }
                }
            }
        }
    }
}
