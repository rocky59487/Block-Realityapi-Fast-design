import os
import re

impl_dir = "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/impl/"

all_nodes = []
for root, _, files in os.walk(impl_dir):
    for file in files:
        if file.endswith("Node.java"):
            rel_path = os.path.relpath(root, impl_dir)
            category = rel_path.split(os.sep)[0] if rel_path != "." else "misc"
            filepath = os.path.join(root, file)
            with open(filepath, 'r') as f:
                content = f.read()

            type_id_match = re.search(r'public\s+String\s+typeId\(\)\s*\{\s*return\s*"([^"]+)";\s*\}', content)
            if not type_id_match: continue
            type_id = type_id_match.group(1)

            name_match = re.search(r'super\("([^"]+)",\s*"([^"]+)"', content)
            name_en = name_match.group(1) if name_match else file.replace("Node.java", "")
            name_cn = name_match.group(2) if name_match else name_en

            package_match = re.search(r'package\s+([^;]+);', content)
            pkg = package_match.group(1)
            fqn = f"{pkg}.{file[:-5]}"

            all_nodes.append({
                "type_id": type_id,
                "fqn": fqn,
                "en": name_en,
                "cn": name_cn,
                "category": category
            })

registry_path = "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java"
with open(registry_path, 'r') as f:
    registry_content = f.read()

registered_type_ids = set()
for match in re.finditer(r'reg\("([^"]+)"', registry_content):
    registered_type_ids.add(match.group(1))

missing_nodes = [n for n in all_nodes if n["type_id"] not in registered_type_ids]

if missing_nodes:
    lines = registry_content.split('\n')
    new_lines = []

    for line in lines:
        if "syncToApiNodeGraphIO();" in line:
            new_lines.append("        // ═══ Automatically Registered Nodes ═══")
            for n in missing_nodes:
                new_lines.append(f'        reg("{n["type_id"]}", {n["fqn"]}::new, "{n["en"]}", "{n["cn"]}", "{n["category"]}");')
            new_lines.append("")
        new_lines.append(line)

    with open(registry_path, 'w') as f:
        f.write('\n'.join(new_lines))
