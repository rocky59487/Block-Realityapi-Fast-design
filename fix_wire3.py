import sys

filepath = "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/canvas/WireRenderer.java"

with open(filepath, "r") as f:
    content = f.read()

# We still have a dangling `        }\n    }\n` at line 81.
new_content = content.replace("    }\n\n    \n        }\n    }\n", "    }\n")

with open(filepath, "w") as f:
    f.write(new_content)
