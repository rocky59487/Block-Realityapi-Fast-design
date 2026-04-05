import os
import re

file_path = 'Block Reality/api/src/main/java/com/blockreality/api/spi/ModuleRegistry.java'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

getters = re.findall(r'public static (\w+) get(\w+)\(\)', content)
setters = re.findall(r'public static void set(\w+)\((\w+) (\w+)\)', content)

print("SPI Methods:")
for ret, name in getters:
    print(f"Getter: get{name} -> {ret}")

for name, typ, arg in setters:
    print(f"Setter: set{name}({typ})")
