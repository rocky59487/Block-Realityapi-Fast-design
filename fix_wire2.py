import sys

filepath = "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/canvas/WireRenderer.java"

with open(filepath, "r") as f:
    content = f.read()

# We need to remove lines 80 to 104
content = content[:content.find("        float[] fromPos = NodeWidgetRenderer.getPortScreenPos(wire.from(), transform);", content.find("        // 自動轉換標記") + 50)]

# Oh wait, we just need to replace the duplicated chunk.
with open(filepath, "r") as f:
    lines = f.readlines()

new_lines = []
skip = False
for i, line in enumerate(lines):
    if line.strip() == "float[] fromPos = NodeWidgetRenderer.getPortScreenPos(wire.from(), transform);" and i > 50:
        skip = True

    if skip and line.strip() == "}":
        skip = False
        continue

    if not skip:
        new_lines.append(line)

with open(filepath, "w") as f:
    f.writelines(new_lines)
