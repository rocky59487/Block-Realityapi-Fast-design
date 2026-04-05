import os
import re

api_dir = 'Block Reality/api/src/main/java/com/blockreality/api'
client_imports = re.compile(r'import com\.blockreality\.api\.client\..*')

leaks = []

for root, dirs, files in os.walk(api_dir):
    # Skip the client directory itself
    if '/client' in root:
        continue

    for file in files:
        if file.endswith('.java'):
            filepath = os.path.join(root, file)
            with open(filepath, 'r', encoding='utf-8') as f:
                content = f.read()
                matches = client_imports.findall(content)
                if matches:
                    # check if the file has @OnlyIn(Dist.CLIENT) at the top class level
                    if '@OnlyIn(Dist.CLIENT)' not in content:
                         leaks.append((filepath, matches))

if leaks:
    print("Client leaks found in non-client code:")
    for leak in leaks:
        print(f"{leak[0]}: {leak[1]}")
else:
    print("No client leaks found.")
