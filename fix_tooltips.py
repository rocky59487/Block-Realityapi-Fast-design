import re

with open("Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/ControlPanelScreen.java", "r") as f:
    content = f.read()

# Add tooltips to section 1
content = content.replace('addBuildButton(sec1X,               sec1Y,      "實心方塊", FdActionPacket.Action.BUILD_SOLID);', 'addBuildButton(sec1X,               sec1Y,      "實心方塊", FdActionPacket.Action.BUILD_SOLID, "填滿實心方塊");')
content = content.replace('addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y,  "空心牆壁", FdActionPacket.Action.BUILD_WALLS);', 'addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y,  "空心牆壁", FdActionPacket.Action.BUILD_WALLS, "建造空心牆壁");')
content = content.replace('addBuildButton(sec1X,               sec1Y + BTN_H + BTN_GAP, "拱門",   FdActionPacket.Action.BUILD_ARCH);', 'addBuildButton(sec1X,               sec1Y + BTN_H + BTN_GAP, "拱門",   FdActionPacket.Action.BUILD_ARCH, "建造拱門形狀");')
content = content.replace('addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + BTN_H + BTN_GAP, "斜撐",   FdActionPacket.Action.BUILD_BRACE);', 'addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + BTN_H + BTN_GAP, "斜撐",   FdActionPacket.Action.BUILD_BRACE, "建造斜撐結構");')
content = content.replace('addBuildButton(sec1X,               sec1Y + (BTN_H + BTN_GAP) * 2, "樓板",   FdActionPacket.Action.BUILD_SLAB);', 'addBuildButton(sec1X,               sec1Y + (BTN_H + BTN_GAP) * 2, "樓板",   FdActionPacket.Action.BUILD_SLAB, "建造樓板結構");')
content = content.replace('addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + (BTN_H + BTN_GAP) * 2, "鋼筋網", FdActionPacket.Action.BUILD_REBAR);', 'addBuildButton(sec1X + BTN_W + BTN_GAP, sec1Y + (BTN_H + BTN_GAP) * 2, "鋼筋網", FdActionPacket.Action.BUILD_REBAR, "配置鋼筋網");')

# Add tooltips to section 3
content = content.replace('addActionButton(sec3X,               sec3Y,                         "複製", FdActionPacket.Action.COPY);', 'addActionButton(sec3X,               sec3Y,                         "複製", FdActionPacket.Action.COPY, "複製選取的方塊");')
content = content.replace('addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y,                    "粘貼預覽", FdActionPacket.Action.PASTE);', 'addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y,                    "粘貼預覽", FdActionPacket.Action.PASTE, "預覽剪貼簿內容");')
content = content.replace('addActionButton(sec3X,               sec3Y + BTN_H + BTN_GAP,      "鏡像 X",\n            FdActionPacket.Action.MIRROR, () -> "x");', 'addActionButton(sec3X,               sec3Y + BTN_H + BTN_GAP,      "鏡像 X",\n            FdActionPacket.Action.MIRROR, () -> "x", "沿 X 軸鏡像");')
content = content.replace('addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + BTN_H + BTN_GAP,  "旋轉 90°",\n            FdActionPacket.Action.ROTATE, () -> "90");', 'addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + BTN_H + BTN_GAP,  "旋轉 90°",\n            FdActionPacket.Action.ROTATE, () -> "90", "順時針旋轉 90 度");')
content = content.replace('addBuildButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 2, "填充", FdActionPacket.Action.FILL);', 'addBuildButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 2, "填充", FdActionPacket.Action.FILL, "填滿選取區域");')
content = content.replace('addBuildButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 2, "替換", FdActionPacket.Action.REPLACE);', 'addBuildButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 2, "替換", FdActionPacket.Action.REPLACE, "替換選取區域內的方塊");')
content = content.replace('addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 3, "清除", FdActionPacket.Action.CLEAR);', 'addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 3, "清除", FdActionPacket.Action.CLEAR, "清除選取區域內的方塊");')
content = content.replace('addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 3, "還原", FdActionPacket.Action.UNDO);', 'addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 3, "還原", FdActionPacket.Action.UNDO, "復原上一步操作");')
content = content.replace('addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 4, "✓ 確認放置", FdActionPacket.Action.PASTE_CONFIRM);', 'addActionButton(sec3X,               sec3Y + (BTN_H + BTN_GAP) * 4, "✓ 確認放置", FdActionPacket.Action.PASTE_CONFIRM, "確認並放置預覽的方塊");')
content = content.replace('addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 4, "✗ 取消貼上", FdActionPacket.Action.PASTE_CANCEL);', 'addActionButton(sec3X + BTN_W + BTN_GAP, sec3Y + (BTN_H + BTN_GAP) * 4, "✗ 取消貼上", FdActionPacket.Action.PASTE_CANCEL, "取消貼上操作");')

# Add tooltips to section 4
content = content.replace('addActionButton(sec4X,                     advBtnY, "儲存藍圖",\n            FdActionPacket.Action.SAVE, () -> blueprintNameBox.getValue());', 'addActionButton(sec4X,                     advBtnY, "儲存藍圖",\n            FdActionPacket.Action.SAVE, () -> blueprintNameBox.getValue(), "將目前選取範圍儲存為藍圖");')
content = content.replace('addActionButton(sec4X + BTN_W + BTN_GAP,  advBtnY, "載入藍圖",\n            FdActionPacket.Action.LOAD, () -> blueprintNameBox.getValue());', 'addActionButton(sec4X + BTN_W + BTN_GAP,  advBtnY, "載入藍圖",\n            FdActionPacket.Action.LOAD, () -> blueprintNameBox.getValue(), "從藍圖檔案載入結構");')
content = content.replace('addActionButton(sec4X,                     advBtnY, "NURBS 匯出", FdActionPacket.Action.EXPORT);', 'addActionButton(sec4X,                     advBtnY, "NURBS 匯出", FdActionPacket.Action.EXPORT, "匯出為 NURBS 格式");')

content = content.replace('Button.builder(Component.literal("全息切換"), btn -> {\n                HologramState.toggleVisible();\n            })', 'Button.builder(Component.literal("全息切換"), btn -> {\n                HologramState.toggleVisible();\n            }).tooltip(Tooltip.create(Component.literal("切換全息投影顯示")))')

content = content.replace('addActionButton(sec4X,                     advBtnY, "CAD 檢視",\n            FdActionPacket.Action.OPEN_CAD);', 'addActionButton(sec4X,                     advBtnY, "CAD 檢視",\n            FdActionPacket.Action.OPEN_CAD, "開啟 CAD 檢視畫面");')

content = content.replace('Button.builder(Component.literal("§a節點編輯器"), btn -> {\n                Minecraft.getInstance().setScreen(new NodeCanvasScreen());\n            })', 'Button.builder(Component.literal("§a節點編輯器"), btn -> {\n                Minecraft.getInstance().setScreen(new NodeCanvasScreen());\n            }).tooltip(Tooltip.create(Component.literal("開啟節點編輯器")))')

content = content.replace('Button.builder(Component.literal("§b視訊設定"), btn -> {\n                Minecraft.getInstance().setScreen(new SimplifiedSettingsScreen(this));\n            })', 'Button.builder(Component.literal("§b視訊設定"), btn -> {\n                Minecraft.getInstance().setScreen(new SimplifiedSettingsScreen(this));\n            }).tooltip(Tooltip.create(Component.literal("開啟視訊設定")))')

content = content.replace('Button.builder(Component.literal("✕ 關閉"), btn -> onClose())', 'Button.builder(Component.literal("✕ 關閉"), btn -> onClose()).tooltip(Tooltip.create(Component.literal("關閉控制面板")))')


# Update method signatures
content = content.replace('private void addBuildButton(int x, int y, String label, FdActionPacket.Action action) {', 'private void addBuildButton(int x, int y, String label, FdActionPacket.Action action, String tooltipText) {')
content = content.replace('FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action, payload));\n            })', 'FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action, payload));\n            }).tooltip(Tooltip.create(Component.literal(tooltipText)))')


content = content.replace('private void addActionButton(int x, int y, String label, FdActionPacket.Action action) {', 'private void addActionButton(int x, int y, String label, FdActionPacket.Action action, String tooltipText) {')
content = content.replace('FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action));\n            })', 'FdNetwork.CHANNEL.sendToServer(new FdActionPacket(action));\n            }).tooltip(Tooltip.create(Component.literal(tooltipText)))')

content = content.replace('private void addActionButton(int x, int y, String label,\n                                  FdActionPacket.Action action,\n                                  java.util.function.Supplier<String> payloadSupplier) {', 'private void addActionButton(int x, int y, String label,\n                                  FdActionPacket.Action action,\n                                  java.util.function.Supplier<String> payloadSupplier,\n                                  String tooltipText) {')
content = content.replace('new FdActionPacket(action, payloadSupplier.get()));\n            })', 'new FdActionPacket(action, payloadSupplier.get()));\n            }).tooltip(Tooltip.create(Component.literal(tooltipText)))')

# Add Tooltip import if missing
if 'import net.minecraft.client.gui.components.Tooltip;' not in content:
    content = content.replace('import net.minecraft.client.gui.screens.Screen;', 'import net.minecraft.client.gui.screens.Screen;\nimport net.minecraft.client.gui.components.Tooltip;')

with open("Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/ControlPanelScreen.java", "w") as f:
    f.write(content)
