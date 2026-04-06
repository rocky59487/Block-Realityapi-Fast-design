import os
import re

files_to_fix = [
    "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/HologramSyncPacket.java",
    "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/PastePreviewSyncPacket.java",
    "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/FdSelectionSyncPacket.java",
    "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/OpenCadScreenPacket.java"
]

for filepath in files_to_fix:
    if not os.path.exists(filepath): continue
    with open(filepath, 'r') as f:
        content = f.read()

    if "OpenCadScreenPacket.java" in filepath:
        content = re.sub(r'import com\.blockreality\.fastdesign\.client\.FastDesignScreen;\n', '', content)
        content = re.sub(r'import net\.minecraft\.client\.Minecraft;\n', '', content)
        content = content.replace("Minecraft.getInstance().setScreen(new FastDesignScreen(bp));",
                                  "net.minecraft.client.Minecraft.getInstance().setScreen(new com.blockreality.fastdesign.client.FastDesignScreen(bp));")

    elif "FdSelectionSyncPacket.java" in filepath:
        content = re.sub(r'import com\.blockreality\.fastdesign\.client\.ClientSelectionHolder;\n', '', content)
        content = content.replace("ClientSelectionHolder.update(pkt.min, pkt.max);",
                                  "com.blockreality.fastdesign.client.ClientSelectionHolder.update(pkt.min, pkt.max);")
        content = content.replace("ClientSelectionHolder.clear();",
                                  "com.blockreality.fastdesign.client.ClientSelectionHolder.clear();")

    elif "PastePreviewSyncPacket.java" in filepath:
        content = re.sub(r'import com\.blockreality\.fastdesign\.client\.GhostPreviewRenderer;\n', '', content)
        content = content.replace("GhostPreviewRenderer.setPreview(pkt.blocks, pkt.origin);",
                                  "com.blockreality.fastdesign.client.GhostPreviewRenderer.setPreview(pkt.blocks, pkt.origin);")
        content = content.replace("GhostPreviewRenderer.clearPreview();",
                                  "com.blockreality.fastdesign.client.GhostPreviewRenderer.clearPreview();")

    elif "HologramSyncPacket.java" in filepath:
        content = re.sub(r'import com\.blockreality\.fastdesign\.client\.HologramState;\n', '', content)
        content = content.replace("HologramState.load(bp, new BlockPos(pkt.originX, pkt.originY, pkt.originZ));",
                                  "com.blockreality.fastdesign.client.HologramState.load(bp, new BlockPos(pkt.originX, pkt.originY, pkt.originZ));")
        content = content.replace("HologramState.clear();", "com.blockreality.fastdesign.client.HologramState.clear();")
        content = content.replace("HologramState.setOffset(pkt.dx, pkt.dy, pkt.dz);", "com.blockreality.fastdesign.client.HologramState.setOffset(pkt.dx, pkt.dy, pkt.dz);")
        content = content.replace("HologramState.rotate();", "com.blockreality.fastdesign.client.HologramState.rotate();")

    with open(filepath, 'w') as f:
        f.write(content)
