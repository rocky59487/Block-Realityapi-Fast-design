import os
import re

client_dir = "Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/"
client_imports_pattern = re.compile(r'import\s+(net\.minecraft\.client\..*|com\.mojang\.blaze3d\..*|.*\.client\..*);')

def needs_annotation(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    if "@OnlyIn(Dist.CLIENT)" in content or "@Mod.EventBusSubscriber" in content:
        return False, None
    if client_imports_pattern.search(content) or "RenderSystem" in content or "PoseStack" in content or "GuiGraphics" in content:
        return True, content
    return False, None

for root, _, files in os.walk(client_dir):
    for file in files:
        if file.endswith(".java"):
            filepath = os.path.join(root, file)
            needs, content = needs_annotation(filepath)
            if needs:
                if "import net.minecraftforge.api.distmarker.Dist;" not in content:
                    content = re.sub(r'(import .*;\n)', r'\1import net.minecraftforge.api.distmarker.Dist;\n', content, count=1)
                if "import net.minecraftforge.api.distmarker.OnlyIn;" not in content:
                    content = re.sub(r'(import .*;\n)', r'\1import net.minecraftforge.api.distmarker.OnlyIn;\n', content, count=1)

                content = re.sub(r'(public\s+(?:abstract\s+)?(?:final\s+)?class\s+)', r'@OnlyIn(Dist.CLIENT)\n\1', content, count=1)
                content = re.sub(r'(public\s+(?:abstract\s+)?(?:final\s+)?interface\s+)', r'@OnlyIn(Dist.CLIENT)\n\1', content, count=1)

                with open(filepath, 'w') as f:
                    f.write(content)
