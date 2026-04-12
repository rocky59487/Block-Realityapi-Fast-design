#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Block Reality RAG → Obsidian Vault Exporter (v2, Pretty Edition)
將 JSON 知識庫轉換為 Obsidian Markdown Vault，含 frontmatter、wiki-link、MOC、標籤、Callout
"""

import json
import re
from pathlib import Path
from collections import defaultdict

RAG_DIR = Path(__file__).parent
VAULT_DIR = RAG_DIR / "obsidian"

# ============================================================
# Helpers
# ============================================================

def sanitize_filename(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|]', '-', name)
    name = name.strip()
    if len(name) > 120:
        name = name[:120]
    return name or "Untitled"

def wikilink(title: str, title_to_file: dict) -> str:
    if title in title_to_file:
        return f"[[{title}]]"
    return title

def short_name(fqcn: str) -> str:
    return fqcn.split(".")[-1] if "." in fqcn else fqcn

def build_frontmatter(entry: dict, extra: dict = None) -> str:
    lines = ["---"]
    if entry.get("id"):
        lines.append(f'id: "{entry["id"]}"')
    if entry.get("type"):
        lines.append(f'type: {entry["type"]}')
    tags = entry.get("tags", [])
    if tags:
        lines.append(f'tags: {json.dumps(tags, ensure_ascii=False)}')
    md = dict(entry.get("metadata", {}))
    if extra:
        md.update(extra)
    # pick clean fields for frontmatter
    for k in ["fqn", "thread_safety", "severity", "command", "related_test", "fix_script"]:
        if k in md:
            v = md[k]
            if isinstance(v, str):
                lines.append(f'{k}: "{v}"')
            elif isinstance(v, list):
                lines.append(f'{k}: {json.dumps(v, ensure_ascii=False)}')
    lines.append("---")
    return "\n".join(lines)

# ============================================================
# Title map
# ============================================================

def collect_all_titles() -> dict:
    titles = {}
    def add(title: str, filename: str):
        if not title:
            return
        short = short_name(title)
        if short not in titles:
            titles[short] = filename
        if title not in titles:
            titles[title] = filename

    index_files = [
        "index_architecture.json", "index_rules.json", "index_spi.json",
        "index_pfsf_physics.json", "index_rendering.json", "index_nodes.json",
        "index_python_ml.json", "index_patterns.json", "index_troubleshooting.json",
        "index_native.json", "index_gradle_build.json", "index_dataflow.json",
        "index_toolchain.json", "index_test_coverage.json", "index_key_classes.json",
    ]
    for fname in index_files:
        p = RAG_DIR / fname
        if not p.exists():
            continue
        data = json.loads(p.read_text(encoding="utf-8"))
        for entry in data.get("entries", []):
            title = entry.get("title", "")
            fn = sanitize_filename(title) + ".md"
            add(title, fn)

    chunks = json.loads((RAG_DIR / "chunks.json").read_text(encoding="utf-8"))
    for ch in chunks:
        title = ch.get("title", "")
        ctype = ch.get("type", "")
        if ctype in ("class", "test", "shader", "cpp_class"):
            fn = sanitize_filename(title) + ".md"
            add(title, fn)
        elif ctype == "method":
            owner = ch.get("metadata", {}).get("owner_class") or "Unknown"
            short_owner = short_name(owner)
            method_title = ch.get("title", "").split(".")[-1]
            method_name = f"{short_owner}#{method_title}"
            fn = sanitize_filename(method_name) + ".md"
            add(method_name, fn)
            full = ch.get("title", "")
            fn2 = sanitize_filename(full) + ".md"
            add(full, fn2)
    return titles

# ============================================================
# Link extraction
# ============================================================

def extract_related_links(entry: dict, title_to_file: dict) -> list:
    links = set()
    text = f"{entry.get('title', '')} {entry.get('summary', '')} {entry.get('content', '')}"
    md = entry.get("metadata", {})
    for rf in md.get("related_files", []):
        if "/" in rf and rf.endswith(".java"):
            cls = rf.split("/")[-1].replace(".java", "")
            if cls in title_to_file and cls != short_name(entry.get("title", "")):
                links.add(cls)
        elif rf.endswith(".py"):
            mod = rf.split("/")[-1].replace(".py", "")
            if mod in title_to_file:
                links.add(mod)
    if md.get("extends"):
        ext = short_name(md["extends"])
        if ext in title_to_file:
            links.add(ext)
    for impl in md.get("implements", []):
        impl_name = short_name(impl)
        if impl_name in title_to_file:
            links.add(impl_name)
    for t in title_to_file:
        if t != entry.get("title", "") and len(t) > 3 and t in text:
            links.add(t)
    return sorted(links)

# ============================================================
# Formatters per type
# ============================================================

def fmt_metadata_block(md: dict, title_to_file: dict) -> list:
    out = []
    if md.get("signature"):
        out.append(f"> `{md['signature']}`")
    if md.get("path"):
        out.append(f"> 📁 `{md['path']}`")
    if md.get("extends"):
        out.append(f"> 🔼 Extends: {wikilink(short_name(md['extends']), title_to_file)}")
    if md.get("implements"):
        impls = [wikilink(short_name(i), title_to_file) for i in md["implements"]]
        out.append(f"> 🔌 Implements: {', '.join(impls)}")
    if md.get("related_test"):
        out.append(f"> 🧪 Test: {wikilink(short_name(md['related_test']), title_to_file)}")
    if md.get("fix_script"):
        out.append(f"> 🛠️ Fix Script: `{md['fix_script']}`")
    if md.get("command"):
        out.append(f"> ⌨️ Command: `{md['command']}`")
    if md.get("commands"):
        out.append("> ⌨️ Commands:")
        for cmd in md["commands"]:
            out.append(f">   - `{cmd}`")
    if md.get("warning"):
        out.append(f"> ⚠️ **WARNING**: {md['warning']}")
    if md.get("severity"):
        out.append(f"> 🚨 Severity: `{md['severity']}`")
    if md.get("stages"):
        out.append("> 🔄 Pipeline Stages:")
        for st in md["stages"]:
            out.append(f">   - {wikilink(st, title_to_file)}")
    if md.get("related_files"):
        out.append("> 📎 Related Files:")
        for rf in md["related_files"]:
            out.append(f">   - `{rf}`")
    if md.get("related_source"):
        out.append("> 📎 Related Source:")
        for src in md["related_source"]:
            out.append(f">   - {wikilink(short_name(src), title_to_file)}")
    return out

def format_key_class(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🧩 {entry.get('title', 'Untitled')}",
        "",
        "> [!info] Quick Facts",
    ]
    if md.get("fqn"):
        lines.append(f"> **FQN**: `{md['fqn']}`")
    if md.get("thread_safety"):
        lines.append(f"> **Thread Safety**: {md['thread_safety']}")
    lines.append("")

    content = entry.get("content", "")
    if content:
        # Parse common sections in key class content
        parts = re.split(r'\n(?=職責：|主要方法：|修改注意：|核心約定：)', content)
        for part in parts:
            part = part.strip()
            if not part:
                continue
            if part.startswith("職責："):
                lines.append("## 📋 職責")
                lines.append(part.replace("職責：", "").strip())
            elif part.startswith("核心約定："):
                lines.append("## 📜 核心約定")
                lines.append(part.replace("核心約定：", "").strip())
            elif part.startswith("主要方法："):
                lines.append("## 🔧 主要方法")
                lines.append(part.replace("主要方法：", "").strip())
            elif part.startswith("修改注意："):
                lines.append("## ⚠️ 修改注意")
                lines.append(part.replace("修改注意：", "").strip())
            else:
                lines.append("## 📝 說明")
                lines.append(part)
            lines.append("")

    if md.get("warning"):
        lines.append("> [!warning] WARNING")
        lines.append(f"> {md['warning']}")
        lines.append("")

    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] Metadata")
        lines.extend(meta_block)
        lines.append("")

    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_troubleshooting(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🚑 {entry.get('title', 'Untitled')}",
        "",
    ]
    content = entry.get("content", "")
    if content:
        # Try to parse 症狀/原因/解決 pattern
        sections = re.split(r'\n(?=症狀：|原因：|解決：|可能原因：|排查：)', content)
        for sec in sections:
            sec = sec.strip()
            if sec.startswith("症狀："):
                lines.append("> [!failure] 症狀")
                lines.append(f"> {sec.replace('症狀：', '').strip()}")
                lines.append("")
            elif sec.startswith("原因："):
                lines.append("> [!bug] 原因")
                lines.append(f"> {sec.replace('原因：', '').strip()}")
                lines.append("")
            elif sec.startswith("可能原因："):
                lines.append("> [!bug] 可能原因")
                lines.append(f"> {sec.replace('可能原因：', '').strip()}")
                lines.append("")
            elif sec.startswith("解決："):
                lines.append("## ✅ 解決方式")
                # Turn numbered items into list
                body = sec.replace("解決：", "").strip()
                # split by digit. digit.
                items = re.split(r'(?=\d+\.)', body)
                for item in items:
                    item = item.strip()
                    if item:
                        lines.append(f"- {item}")
                lines.append("")
            elif sec.startswith("排查："):
                lines.append("## 🔍 排查建議")
                body = sec.replace("排查：", "").strip()
                items = re.split(r'(?=\d+\.)', body)
                for item in items:
                    item = item.strip()
                    if item:
                        lines.append(f"- {item}")
                lines.append("")
            else:
                lines.append("## 📝 說明")
                lines.append(sec)
                lines.append("")

    if md.get("severity"):
        lines.append(f"> [!danger] Severity: `{md['severity']}`")
        lines.append("")

    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")

    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_pattern(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🧩 {entry.get('title', 'Untitled')}",
        "",
    ]
    content = entry.get("content", "")
    if content:
        # Split steps like "1. xxx 2. xxx"
        steps = re.split(r'步驟：', content)
        if len(steps) > 1:
            preamble = steps[0].strip()
            if preamble:
                lines.append("## 📝 說明")
                lines.append(preamble)
                lines.append("")
            body = steps[1].strip()
            items = re.split(r'(?=\d+\.)', body)
            lines.append("## 🪜 步驟")
            for item in items:
                item = item.strip()
                if item:
                    lines.append(f"- {item}")
            lines.append("")
        else:
            lines.append("## 📝 說明")
            lines.append(content)
            lines.append("")

    if md.get("example_class"):
        lines.append(f"> [!example] 範例類別: {wikilink(md['example_class'], title_to_file)}")
        lines.append("")

    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")

    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_dataflow(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🌊 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("content"):
        lines.append("## 📝 概述")
        lines.append(entry["content"])
        lines.append("")

    stages = md.get("stages", [])
    if stages:
        lines.append("## 🔄 資料流階段")
        for i, st in enumerate(stages, 1):
            lines.append(f"{i}. {wikilink(st, title_to_file)}")
        lines.append("")

    if md.get("warning"):
        lines.append("> [!warning] 注意")
        lines.append(f"> {md['warning']}")
        lines.append("")
    if md.get("note"):
        lines.append("> [!info] 備註")
        lines.append(f"> {md['note']}")
        lines.append("")

    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")

    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_spi(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🔌 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("content"):
        lines.append("## 📝 說明")
        lines.append(entry["content"])
        lines.append("")
    if md.get("interface"):
        lines.append(f"> [!info] Interface")
        lines.append(f"> `{md['interface']}`")
        lines.append("")
    if md.get("default_impl"):
        lines.append(f"> [!info] 預設實作")
        lines.append(f"> `{md['default_impl']}`")
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_test_coverage(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🧪 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("content"):
        lines.append("## 📝 適用場景")
        lines.append(entry["content"])
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_tool(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🛠️ {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("content"):
        lines.append("## 📝 說明")
        lines.append(entry["content"])
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 使用資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_build(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🏗️ {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("content"):
        lines.append("## 📝 說明")
        lines.append(entry["content"])
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 建置資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_generic_handwritten(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 📄 {entry.get('title', 'Untitled')}",
        "",
    ]
    summary = entry.get("summary", "")
    content = entry.get("content", "")
    if summary:
        lines.append("## 📝 摘要")
        lines.append(summary)
        lines.append("")
    if content and content != summary:
        lines.append("## 📖 內容")
        lines.append(content)
        lines.append("")
    if md.get("warning"):
        lines.append("> [!warning] 注意")
        lines.append(f"> {md['warning']}")
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 相關資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related Notes")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_auto_class(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🧩 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("summary"):
        lines.append("> [!info] 摘要")
        lines.append(f"> {entry['summary']}")
        lines.append("")
    meta_block = fmt_metadata_block(md, title_to_file)
    if meta_block:
        lines.append("> [!tip] 資訊")
        lines.extend(meta_block)
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_auto_method(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🔧 {entry.get('title', 'Untitled')}",
        "",
    ]
    sig = md.get("signature", "")
    if sig:
        lines.append(f"> [!info] Signature")
        lines.append(f"> `{sig}`")
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_auto_shader(entry: dict, title_to_file: dict) -> str:
    md = entry.get("metadata", {})
    lines = [
        build_frontmatter(entry),
        "",
        f"# 🎨 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("summary"):
        lines.append(f"> [!info] 摘要")
        lines.append(f"> {entry['summary']}")
        lines.append("")
    funcs = md.get("functions", [])
    if funcs:
        lines.append("## 🧩 Functions")
        lines.append(", ".join([f"`{f}`" for f in funcs]))
        lines.append("")
    unis = md.get("uniforms", [])
    if unis:
        lines.append("## 📦 Uniforms")
        lines.append(", ".join([f"`{u}`" for u in unis]))
        lines.append("")
    rel = extract_related_links(entry, title_to_file)
    if rel:
        lines.append("## 🔗 Related")
        lines.extend([f"- {wikilink(r, title_to_file)}" for r in rel])
        lines.append("")
    return "\n".join(lines)

def format_doc_chunk(entry: dict, title_to_file: dict) -> str:
    lines = [
        "---",
        f'id: "{entry.get("id", "")}"',
        f'type: {entry.get("type", "doc")}',
        'tags: [doc, markdown]',
        f'filename: "{entry.get("metadata", {}).get("filename", "")}"',
        f'section: "{entry.get("metadata", {}).get("section", "")}"',
        "---",
        "",
        f"# 📄 {entry.get('title', 'Untitled')}",
        "",
    ]
    if entry.get("summary"):
        lines.append(entry["summary"])
        lines.append("")
    return "\n".join(lines)

# ============================================================
# Dispatch
# ============================================================

FORMATTERS = {
    "key_class": format_key_class,
    "troubleshooting": format_troubleshooting,
    "pattern": format_pattern,
    "dataflow": format_dataflow,
    "spi": format_spi,
    "test_coverage": format_test_coverage,
    "tool": format_tool,
    "build": format_build,
}

def render_entry(entry: dict, title_to_file: dict) -> str:
    ctype = entry.get("type", "")
    if ctype in FORMATTERS:
        return FORMATTERS[ctype](entry, title_to_file)
    return format_generic_handwritten(entry, title_to_file)

# ============================================================
# Export
# ============================================================

def write_note(folder: Path, entry: dict, title_to_file: dict, body: str = None) -> str:
    title = entry.get("title", "Untitled")
    filename = sanitize_filename(title) + ".md"
    filepath = folder / filename
    counter = 1
    orig = filepath
    while filepath.exists():
        filepath = folder / f"{orig.stem}-{counter}.md"
        counter += 1
    filepath.parent.mkdir(parents=True, exist_ok=True)
    text = body if body is not None else render_entry(entry, title_to_file)
    filepath.write_text(text, encoding="utf-8")
    return filepath.name

def export_handwritten_indices(title_to_file: dict) -> dict:
    moc = defaultdict(list)
    mappings = [
        ("01 - Architecture", ["index_architecture.json"]),
        ("01 - Architecture/Rules", ["index_rules.json"]),
        ("02 - SPI", ["index_spi.json"]),
        ("03 - Physics/Concepts", ["index_pfsf_physics.json"]),
        ("03 - Physics/Dataflows", ["index_dataflow.json"]),
        ("04 - Rendering/Concepts", ["index_rendering.json"]),
        ("04 - Rendering/Native & Shaders", ["index_native.json"]),
        ("05 - Nodes/Concepts", ["index_nodes.json"]),
        ("05 - Nodes/Patterns", ["index_patterns.json"]),
        ("06 - ML", ["index_python_ml.json"]),
        ("07 - Toolchain", ["index_toolchain.json"]),
        ("07 - Toolchain/Build", ["index_gradle_build.json"]),
        ("08 - Key Classes", ["index_key_classes.json"]),
        ("09 - Troubleshooting", ["index_troubleshooting.json"]),
        ("10 - Test Coverage", ["index_test_coverage.json"]),
    ]
    for folder_name, json_files in mappings:
        folder = VAULT_DIR / folder_name
        for jf in json_files:
            p = RAG_DIR / jf
            if not p.exists():
                continue
            data = json.loads(p.read_text(encoding="utf-8"))
            for entry in data.get("entries", []):
                fn = write_note(folder, entry, title_to_file)
                moc[folder_name].append((entry.get("title", "Untitled"), fn))
    return dict(moc)

def export_auto_chunks(title_to_file: dict) -> dict:
    moc = defaultdict(list)
    chunks = json.loads((RAG_DIR / "chunks.json").read_text(encoding="utf-8"))

    class_folder = VAULT_DIR / "99 - Auto/Classes"
    test_folder = VAULT_DIR / "99 - Auto/Tests"
    shader_folder = VAULT_DIR / "99 - Auto/Shaders"
    method_by_class = defaultdict(list)

    for ch in chunks:
        ctype = ch.get("type", "")
        if ctype == "class":
            fn = write_note(class_folder, ch, title_to_file, format_auto_class(ch, title_to_file))
            moc["99 - Auto/Classes"].append((ch.get("title", ""), fn))
        elif ctype == "test":
            fn = write_note(test_folder, ch, title_to_file, format_auto_class(ch, title_to_file))
            moc["99 - Auto/Tests"].append((ch.get("title", ""), fn))
        elif ctype == "shader":
            fn = write_note(shader_folder, ch, title_to_file, format_auto_shader(ch, title_to_file))
            moc["99 - Auto/Shaders"].append((ch.get("title", ""), fn))
        elif ctype == "method":
            owner = ch.get("metadata", {}).get("owner_class") or "Unknown"
            short_owner = short_name(owner)
            method_name = ch.get("title", "").split(".")[-1]
            display = f"{short_owner}#{method_name}"
            ch_copy = dict(ch)
            ch_copy["title"] = display
            folder = VAULT_DIR / f"99 - Auto/Methods/{short_owner}"
            fn = write_note(folder, ch_copy, title_to_file, format_auto_method(ch_copy, title_to_file))
            method_by_class[short_owner].append((display, fn))

    for owner, items in method_by_class.items():
        moc[f"99 - Auto/Methods/{owner}"] = items
    return dict(moc)

# ============================================================
# MOC
# ============================================================

def create_moc_pages(moc_data: dict):
    moc_dir = VAULT_DIR / "00 - MOC"
    moc_dir.mkdir(parents=True, exist_ok=True)

    home_lines = [
        "---",
        "id: moc-home",
        'type: "moc"',
        "tags: [moc, overview]",
        "---",
        "",
        "# 🏠 Block Reality 知識庫總覽",
        "",
        "> [!info] 關於這個知識庫",
        "> 這是由 RAG 索引自動匯出的 Obsidian Vault，供人類可視化閱讀與快速導航。",
        "",
        "## 🧭 主題導航",
        "",
        "| 主題 | MOC | 內容 |",
        "|------|-----|------|",
        "| 🏛️ 架構 | [[Architecture MOC]] | 模組邊界、規則、守則 |",
        "| 🔌 SPI | [[SPI MOC]] | 擴展點與預設實作 |",
        "| ⚙️ 物理 | [[Physics MOC]] | PFSF 引擎、資料流、GPU 計算 |",
        "| 🎨 渲染 | [[Rendering MOC]] | Vulkan RT、LOD、Shader |",
        "| 🧩 節點 | [[Nodes MOC]] | FastDesign 節點系統 |",
        "| 🤖 ML | [[ML MOC]] | 訓練管線、ONNX、BIFROST |",
        "| 🛠️ 工具鏈 | [[Toolchain MOC]] | Gradle、Fix 腳本、CI |",
        "| 🧩 核心類別 | [[Key Classes MOC]] | 快速參考卡 |",
        "| 🚑 錯誤排查 | [[Troubleshooting MOC]] | 常見錯誤與解決 |",
        "| 🧪 測試覆蓋 | [[Test Coverage MOC]] | 測試與原始碼對應 |",
        "| 🤖 自動匯出 | [[Auto Generated MOC]] | 類別/方法/Shader |",
        "| 🎨 Canvas | [[Canvas MOC]] | 視覺化資料流白板 |",
        "| 🗺️ 精選總覽 | [[Block Reality 總覽.canvas]] | 一張圖看懂專案 |",
        "| 🎓 學習路徑 | [[Learning Path]] | 給新手的閱讀順序 |",
        "| 🤖 Agent 指南 | [[Agent Guide]] | AI Agent 操作手冊 |",
        "",
        "## 🏷️ 常用標籤",
        "",
        "`#critical` `#client-only` `#pfsf` `#node` `#shader` `#pattern` `#troubleshooting` `#test_coverage` `#dataflow`",
        "",
        "## 💡 使用提示",
        "",
        "- 按 `Ctrl+G`（或 `Cmd+G`）開啟 **Graph View**，顏色已預設分類",
        "- 使用左側 **Tags 面板** 快速篩選主題",
        "- `08 - Key Classes/` 的筆記是濃縮快速參考卡，最適合快速查閱",
        "- 新手建議從 [[Learning Path]] 開始，按順序閱讀",
        "- Agent 請先閱讀 [[Agent Guide]] 再開始操作原始碼",
        "- 喜歡圖形化的人直接打開 [[Block Reality 總覽.canvas]]",
    ]
    (moc_dir / "Home.md").write_text("\n".join(home_lines), encoding="utf-8")

    sections = {
        "Architecture MOC": ["01 - Architecture", "01 - Architecture/Rules"],
        "SPI MOC": ["02 - SPI"],
        "Physics MOC": ["03 - Physics/Concepts", "03 - Physics/Dataflows"],
        "Rendering MOC": ["04 - Rendering/Concepts", "04 - Rendering/Native & Shaders"],
        "Nodes MOC": ["05 - Nodes/Concepts", "05 - Nodes/Patterns"],
        "ML MOC": ["06 - ML"],
        "Toolchain MOC": ["07 - Toolchain", "07 - Toolchain/Build"],
        "Key Classes MOC": ["08 - Key Classes"],
        "Troubleshooting MOC": ["09 - Troubleshooting"],
        "Test Coverage MOC": ["10 - Test Coverage"],
    }

    for moc_name, folders in sections.items():
        lines = [
            "---",
            f'id: moc-{moc_name.lower().replace(" ", "-")}',
            'type: "moc"',
            "tags: [moc]",
            "---",
            "",
            f"# {moc_name}",
            "",
        ]
        for folder in folders:
            if folder not in moc_data:
                continue
            display = folder.split("/")[-1]
            lines.append(f"## {display}")
            lines.append("")
            # table format for prettier look
            lines.append("| 標題 | 檔案 |")
            lines.append("|------|------|")
            for title, fn in sorted(moc_data[folder]):
                lines.append(f"| [[{title}]] | `{fn}` |")
            lines.append("")
        # Dataview block
        from_clause = " OR ".join([f'"{f}"' for f in folders])
        lines.append("## 🔍 Dataview 動態查詢")
        lines.append("")
        lines.append("> [!info] 需安裝 Dataview 外掛才能看到動態結果")
        lines.append("")
        lines.append("```dataview")
        lines.append("TABLE type, summary")
        lines.append(f"FROM {from_clause}")
        lines.append('WHERE type != "moc"')
        lines.append("SORT file.name ASC")
        lines.append("LIMIT 50")
        lines.append("```")
        lines.append("")
        lines.append("---")
        lines.append("")
        lines.append("返回 [[Home]]")
        (moc_dir / f"{moc_name}.md").write_text("\n".join(lines), encoding="utf-8")

    # Auto Generated MOC
    auto_lines = [
        "---",
        "id: moc-auto",
        'type: "moc"',
        "tags: [moc, auto]",
        "---",
        "",
        "# 🤖 Auto Generated MOC",
        "",
        "> [!info] 此區為自動從原始碼掃描匯出的筆記",
        "> 數量較多，建議使用 Obsidian 搜尋或 Graph View 瀏覽。",
        "",
        "## 📊 統計",
        "",
        f"- **Classes**: {len(moc_data.get('99 - Auto/Classes', []))} 個",
        f"- **Tests**: {len(moc_data.get('99 - Auto/Tests', []))} 個",
        f"- **Shaders**: {len(moc_data.get('99 - Auto/Shaders', []))} 個",
    ]
    method_folders = [k for k in moc_data if k.startswith("99 - Auto/Methods/")]
    total_methods = sum(len(moc_data[mf]) for mf in method_folders)
    auto_lines.append(f"- **Methods**: {total_methods} 個（分屬 {len(method_folders)} 個類別）")
    auto_lines.extend([
        "",
        "## 🧩 Classes",
        f"路徑：`99 - Auto/Classes/`（{len(moc_data.get('99 - Auto/Classes', []))} notes）",
        "",
        "## 🎨 Shaders",
        f"路徑：`99 - Auto/Shaders/`（{len(moc_data.get('99 - Auto/Shaders', []))} notes）",
        "",
        "## 🧪 Tests",
        f"路徑：`99 - Auto/Tests/`（{len(moc_data.get('99 - Auto/Tests', []))} notes）",
        "",
        "## 🔧 Methods by Owner",
        "",
    ])
    for mf in sorted(method_folders):
        cls = mf.split("/")[-1]
        auto_lines.append(f"- [[{cls} Methods MOC|{cls}]] — {len(moc_data[mf])} methods")
    auto_lines.append("")
    (moc_dir / "Auto Generated MOC.md").write_text("\n".join(auto_lines), encoding="utf-8")

    for mf in sorted(method_folders):
        cls = mf.split("/")[-1]
        lines = [
            "---",
            f'id: moc-methods-{cls}',
            'type: "moc"',
            "tags: [moc, methods]",
            "---",
            "",
            f"# 🔧 {cls} Methods",
            "",
            f"共 {len(moc_data[mf])} 個方法：",
            "",
        ]
        for title, fn in sorted(moc_data[mf]):
            lines.append(f"- [[{title}]]")
        (moc_dir / f"{cls} Methods MOC.md").write_text("\n".join(lines), encoding="utf-8")

# ============================================================
# Folder Notes (README.md in each folder)
# ============================================================

def create_folder_notes(moc_data: dict):
    folder_to_moc = {
        "01 - Architecture": "Architecture MOC",
        "01 - Architecture/Rules": "Architecture MOC",
        "02 - SPI": "SPI MOC",
        "03 - Physics": "Physics MOC",
        "03 - Physics/Concepts": "Physics MOC",
        "03 - Physics/Dataflows": "Physics MOC",
        "04 - Rendering": "Rendering MOC",
        "04 - Rendering/Concepts": "Rendering MOC",
        "04 - Rendering/Native & Shaders": "Rendering MOC",
        "05 - Nodes": "Nodes MOC",
        "05 - Nodes/Concepts": "Nodes MOC",
        "05 - Nodes/Patterns": "Nodes MOC",
        "06 - ML": "ML MOC",
        "07 - Toolchain": "Toolchain MOC",
        "07 - Toolchain/Build": "Toolchain MOC",
        "08 - Key Classes": "Key Classes MOC",
        "09 - Troubleshooting": "Troubleshooting MOC",
        "10 - Test Coverage": "Test Coverage MOC",
        "99 - Auto": "Auto Generated MOC",
        "99 - Auto/Classes": "Auto Generated MOC",
        "99 - Auto/Tests": "Auto Generated MOC",
        "99 - Auto/Shaders": "Auto Generated MOC",
    }
    for folder_name, moc_name in folder_to_moc.items():
        folder = VAULT_DIR / folder_name
        folder.mkdir(parents=True, exist_ok=True)
        emoji_map = {
            "Architecture MOC": "🏛️",
            "SPI MOC": "🔌",
            "Physics MOC": "⚙️",
            "Rendering MOC": "🎨",
            "Nodes MOC": "🧩",
            "ML MOC": "🤖",
            "Toolchain MOC": "🛠️",
            "Key Classes MOC": "🧩",
            "Troubleshooting MOC": "🚑",
            "Test Coverage MOC": "🧪",
            "Auto Generated MOC": "🤖",
        }
        emoji = emoji_map.get(moc_name, "📁")
        lines = [
            "---",
            "tags: [folder-note]",
            "---",
            "",
            f"# {emoji} {folder_name}",
            "",
            f"> [!info] 這是 `{folder_name}` 資料夾的入口筆記",
            "> 點擊下方可查看此區的 MOC 目錄。",
            "",
            f"![[{moc_name}]]",
            "",
            "---",
            "",
            "返回 [[Home]]",
        ]
        (folder / "README.md").write_text("\n".join(lines), encoding="utf-8")

    # Also create README for each method-owner folder
    methods_base = VAULT_DIR / "99 - Auto/Methods"
    if methods_base.exists():
        for sub in sorted(methods_base.iterdir()):
            if sub.is_dir():
                cls = sub.name
                moc_title = f"{cls} Methods MOC"
                lines = [
                    "---",
                    "tags: [folder-note, methods]",
                    "---",
                    "",
                    f"# 🔧 {cls} Methods",
                    "",
                    f"> [!info] 這是 `{cls}` 的方法筆記資料夾",
                    "",
                    f"![[{moc_title}]]",
                    "",
                    "---",
                    "",
                    "返回 [[Auto Generated MOC]] | [[Home]]",
                ]
                (sub / "README.md").write_text("\n".join(lines), encoding="utf-8")

# ============================================================
# Canvas Files
# ============================================================

def create_canvas_files():
    canvas_dir = VAULT_DIR / "00 - Canvas"
    canvas_dir.mkdir(parents=True, exist_ok=True)

    def make_text_node(node_id: str, text: str, x: int, y: int, w: int = 220, h: int = 80, color: str = "1") -> dict:
        return {
            "id": node_id,
            "type": "text",
            "text": text,
            "x": x,
            "y": y,
            "width": w,
            "height": h,
            "color": color,
        }

    def make_file_node(node_id: str, file_path: str, x: int, y: int, w: int = 240, h: int = 100, color: str = "2") -> dict:
        return {
            "id": node_id,
            "type": "file",
            "file": file_path,
            "x": x,
            "y": y,
            "width": w,
            "height": h,
            "color": color,
        }

    def make_edge(edge_id: str, from_node: str, to_node: str, label: str = "") -> dict:
        e = {
            "id": edge_id,
            "fromNode": from_node,
            "fromSide": "right",
            "toNode": to_node,
            "toSide": "left",
        }
        if label:
            e["label"] = label
        return e

    # --- Canvas 1: PFSF Pipeline ---
    pfsf_canvas = {
        "nodes": [
            make_text_node("n1", "🌍 Minecraft World", 0, 200),
            make_file_node("n2", "08 - Key Classes/StructureIslandRegistry 快速參考.md", 300, 200),
            make_file_node("n3", "08 - Key Classes/PFSFDataBuilder 快速參考.md", 600, 200),
            make_text_node("n4", "🎮 GPU Shaders\\n(jacobi / pcg / phase-field)", 900, 200, w=260, h=100, color="3"),
            make_file_node("n5", "08 - Key Classes/PFSFEngine 快速參考.md", 900, 50),
            make_file_node("n6", "08 - Key Classes/OnnxPFSFRuntime 快速參考.md", 600, 50),
            make_file_node("n7", "08 - Key Classes/CollapseManager 快速參考.md", 1200, 200),
        ],
        "edges": [
            make_edge("e1", "n1", "n2", "detect islands"),
            make_edge("e2", "n2", "n3", "snapshot"),
            make_edge("e3", "n3", "n4", "upload buffers"),
            make_edge("e4", "n5", "n4", "dispatch"),
            make_edge("e5", "n6", "n4", "shortcut"),
            make_edge("e6", "n4", "n7", "failure scan"),
        ],
    }

    # --- Canvas 2: Rendering Pipeline ---
    render_canvas = {
        "nodes": [
            make_text_node("r1", "🧱 Chunk Sections", 0, 200),
            make_file_node("r2", "99 - Auto/Classes/SectionMeshCompiler.md", 300, 200),
            make_text_node("r3", "🔺 Mesh / BVH", 600, 200),
            make_file_node("r4", "08 - Key Classes/BRVulkanRT 快速參考.md", 900, 200),
            make_text_node("r5", "💡 Raygen / Hit / Miss Shaders", 1200, 200, w=260, h=100, color="3"),
            make_text_node("r6", "🎨 PostFX + Compose", 1500, 200, w=220, h=80, color="4"),
        ],
        "edges": [
            make_edge("re1", "r1", "r2"),
            make_edge("re2", "r2", "r3"),
            make_edge("re3", "r3", "r4", "build AS"),
            make_edge("re4", "r4", "r5", "dispatch RT"),
            make_edge("re5", "r5", "r6", "denoise + post"),
        ],
    }

    # --- Canvas 3: ML Training Pipeline ---
    ml_canvas = {
        "nodes": [
            make_text_node("m1", "📐 Blueprint / World Data", 0, 200),
            make_text_node("m2", "🧮 FEM Ground Truth", 300, 200),
            make_file_node("m3", "06 - ML/BIFROST ML 訓練與模型註冊.md", 600, 200),
            make_text_node("m4", "🤖 Flax Models\\n(FNO / Surrogate)", 900, 200, w=240, h=100, color="3"),
            make_text_node("m5", "📤 ONNX Export", 1200, 200),
            make_file_node("m6", "08 - Key Classes/OnnxPFSFRuntime 快速參考.md", 1500, 200),
        ],
        "edges": [
            make_edge("me1", "m1", "m2", "dataset"),
            make_edge("me2", "m2", "m3", "labels"),
            make_edge("me3", "m3", "m4", "train"),
            make_edge("me4", "m4", "m5", "export"),
            make_edge("me5", "m5", "m6", "inference"),
        ],
    }

    # --- Canvas 4: Node Evaluation Pipeline ---
    node_canvas = {
        "nodes": [
            make_text_node("no1", "🎨 NodeCanvasScreen", 0, 200),
            make_file_node("no2", "08 - Key Classes/EvaluateScheduler 快速參考.md", 300, 200),
            make_file_node("no3", "08 - Key Classes/BRNode (fastdesign) 快速參考.md", 600, 200),
            make_text_node("no4", "⚡ evaluate()", 900, 200),
            make_file_node("no5", "08 - Key Classes/NodeRegistry 快速參考.md", 600, 50),
            make_text_node("no6", "🔗 Binder Output", 1200, 200),
        ],
        "edges": [
            make_edge("ne1", "no1", "no2", "edit"),
            make_edge("ne2", "no2", "no3", "schedule"),
            make_edge("ne3", "no3", "no4", "compute"),
            make_edge("ne4", "no5", "no3", "factory"),
            make_edge("ne5", "no4", "no6", "bind"),
        ],
    }

    canvases = {
        "PFSF Pipeline.canvas": pfsf_canvas,
        "Rendering Pipeline.canvas": render_canvas,
        "ML Training Pipeline.canvas": ml_canvas,
        "Node Evaluation Pipeline.canvas": node_canvas,
    }

    for filename, canvas_data in canvases.items():
        (canvas_dir / filename).write_text(
            json.dumps(canvas_data, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    # Create a Canvas MOC
    moc_dir = VAULT_DIR / "00 - MOC"
    moc_dir.mkdir(parents=True, exist_ok=True)
    canvas_moc = [
        "---",
        "id: moc-canvas",
        'type: "moc"',
        "tags: [moc, canvas]",
        "---",
        "",
        "# 🎨 Canvas 視覺化白板",
        "",
        "> [!info] 關於 Canvas",
        "> Obsidian Canvas 是視覺化白板，可以拖曳節點、放大縮小檢視資料流。",
        "",
        "## 可用的 Canvas 白板",
        "",
        "| 白板 | 主題 |",
        "|------|------|",
        "| [[PFSF Pipeline.canvas]] | PFSF 物理計算完整資料流 |",
        "| [[Rendering Pipeline.canvas]] | Vulkan RT 渲染管線 |",
        "| [[ML Training Pipeline.canvas]] | ML 訓練到部署流程 |",
        "| [[Node Evaluation Pipeline.canvas]] | FastDesign 節點圖求值 |",
        "",
        "返回 [[Home]]",
    ]
    (moc_dir / "Canvas MOC.md").write_text("\n".join(canvas_moc), encoding="utf-8")

# ============================================================
# Curated Master Map & Learning Path
# ============================================================

def create_curated_canvas():
    """建立精選總覽畫布，只包含高價值手寫筆記，不含大量自動方法節點"""
    canvas_dir = VAULT_DIR / "00 - Canvas"
    canvas_dir.mkdir(parents=True, exist_ok=True)

    def t(id_: str, text: str, x: int, y: int, w: int = 240, h: int = 80, color: str = "1") -> dict:
        return {"id": id_, "type": "text", "text": text, "x": x, "y": y, "width": w, "height": h, "color": color}

    def f(id_: str, path: str, x: int, y: int, w: int = 260, h: int = 100, color: str = "2") -> dict:
        return {"id": id_, "type": "file", "file": path, "x": x, "y": y, "width": w, "height": h, "color": color}

    def e(id_: str, fr: str, to: str, label: str = "", color: str = "") -> dict:
        edge = {"id": id_, "fromNode": fr, "fromSide": "right", "toNode": to, "toSide": "left"}
        if label:
            edge["label"] = label
        if color:
            edge["color"] = color
        return edge

    # Color mapping: 1=gray 2=red 3=orange 4=yellow 5=green 6=cyan 7=blue 8=purple
    nodes = [
        # Center
        t("c0", "# Block Reality\n總覽", 700, 400, w=280, h=120, color="1"),

        # --- Architecture (left, red-ish) ---
        t("la", "## 🏛️ Architecture", 50, 150, w=200, h=60, color="2"),
        f("la1", "00 - MOC/Architecture MOC.md", 50, 230, color="2"),
        f("la2", "01 - Architecture/模組邊界與依賴方向.md", 50, 340, color="2"),
        f("la3", "01 - Architecture/客戶端-伺服器端分離（極重要）.md", 50, 450, color="2"),
        f("la4", "01 - Architecture/Rules/sigmaMax 正規化約定（PFSF 核心）.md", 50, 560, color="2"),
        f("la5", "01 - Architecture/Rules/26 連通一致性（PFSF Shader）.md", 50, 670, color="2"),

        # --- Physics (top-left, blue-ish) ---
        t("lp", "## ⚙️ Physics (PFSF)", 350, 50, w=200, h=60, color="7"),
        f("lp1", "03 - Physics/Concepts/PFSF 引擎總覽.md", 350, 130, color="7"),
        f("lp2", "08 - Key Classes/PFSFDataBuilder 快速參考.md", 350, 240, color="7"),
        f("lp3", "08 - Key Classes/PFSFEngine 快速參考.md", 350, 350, color="7"),
        f("lp4", "03 - Physics/Dataflows/PFSF 物理計算完整資料流.md", 350, 460, color="7"),
        f("lp5", "08 - Key Classes/CollapseManager 快速參考.md", 350, 570, color="7"),
        f("lp6", "08 - Key Classes/RCFusionDetector 快速參考.md", 350, 680, color="7"),

        # --- Rendering (top, purple-ish) ---
        t("lr", "## 🎨 Rendering", 650, -80, w=200, h=60, color="8"),
        f("lr1", "04 - Rendering/Concepts/渲染系統總覽.md", 650, 0, color="8"),
        f("lr2", "08 - Key Classes/BRVulkanRT 快速參考.md", 650, 110, color="8"),
        f("lr3", "04 - Rendering/Concepts/Vulkan RT 渲染管線資料流.md", 650, 220, color="8"),
        f("lr4", "04 - Rendering/Native & Shaders/NRD JNI 原生橋接.md", 650, 330, color="8"),

        # --- Nodes (top-right, green-ish) ---
        t("ln", "## 🧩 Nodes (FastDesign)", 950, 50, w=200, h=60, color="5"),
        f("ln1", "08 - Key Classes/NodeRegistry 快速參考.md", 950, 130, color="5"),
        f("ln2", "08 - Key Classes/BRNode (fastdesign) 快速參考.md", 950, 240, color="5"),
        f("ln3", "05 - Nodes/Patterns/如何新增一個 FastDesign 節點.md", 950, 350, color="5"),
        f("ln4", "05 - Nodes/Concepts/節點核心類別.md", 950, 460, color="5"),
        f("ln5", "05 - Nodes/Patterns/如何安全地撰寫跨端網路封包.md", 950, 570, color="5"),

        # --- ML (right, orange-ish) ---
        t("lm", "## 🤖 ML & ONNX", 1250, 150, w=200, h=60, color="3"),
        f("lm1", "08 - Key Classes/OnnxPFSFRuntime 快速參考.md", 1250, 230, color="3"),
        f("lm2", "06 - ML/BIFROST ML 訓練與模型註冊.md", 1250, 340, color="3"),
        f("lm3", "06 - ML/ML 訓練到部署資料流.md", 1250, 450, color="3"),
        f("lm4", "06 - ML/核心模型架構.md", 1250, 560, color="3"),

        # --- Toolchain / Troubleshooting (bottom-right, yellow/pink) ---
        t("lt", "## 🛠️ Toolchain", 1250, 680, w=200, h=60, color="4"),
        f("lt1", "07 - Toolchain/fix_imports.py — 修復 network 封包中的 client-only import.md", 1250, 760, w=280, h=100, color="4"),
        f("lt2", "07 - Toolchain/Build/常用 Gradle 任務速查.md", 1250, 870, color="4"),

        t("lb", "## 🚑 Troubleshooting", 650, 620, w=200, h=60, color="6"),
        f("lb1", "09 - Troubleshooting/NoClassDefFoundError-client-only 類別在伺服器端載入.md", 650, 700, w=280, h=100, color="6"),
        f("lb2", "09 - Troubleshooting/PFSF GPU 求解發散或收斂極慢.md", 650, 810, w=280, h=100, color="6"),
        f("lb3", "09 - Troubleshooting/節點圖載入時找不到節點類別.md", 650, 920, w=280, h=100, color="6"),

        # --- Essentials / Guide ---
        f("eg1", "00 - Essentials/Learning Path.md", 50, 850, w=240, h=100, color="1"),
        f("eg2", "00 - MOC/Agent Guide.md", 50, 960, w=240, h=100, color="1"),
    ]

    edges = [
        # Architecture -> Center
        e("ea1", "la1", "c0", "rules"),
        e("ea2", "la2", "c0", "boundary"),
        e("ea3", "la4", "lp2", "normalize"),
        # Physics -> Center
        e("ep1", "lp1", "c0", "core engine"),
        e("ep2", "lp4", "lp5", "failure"),
        e("ep3", "lp6", "lp5", "fuse"),
        # Rendering -> Center
        e("er1", "lr1", "c0", "visual"),
        # Nodes -> Center
        e("en1", "ln1", "c0", "registry"),
        e("en2", "ln3", "ln1", "dev flow"),
        # ML -> Center
        e("em1", "lm1", "c0", "inference"),
        e("em2", "lm3", "lm1", "deploy"),
        # Troubleshooting -> relevant clusters
        e("etb1", "lb1", "la3", "cause"),
        e("etb2", "lb2", "lp1", "debug"),
        e("etb3", "lb3", "ln1", "missing reg"),
        # Guide links
        e("eg_l1", "eg1", "c0", "start here"),
    ]

    canvas_data = {"nodes": nodes, "edges": edges}
    (canvas_dir / "Block Reality 總覽.canvas").write_text(
        json.dumps(canvas_data, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )


def create_learning_path_and_guide():
    essentials = VAULT_DIR / "00 - Essentials"
    essentials.mkdir(parents=True, exist_ok=True)

    learning_path = [
        "---",
        "id: learning-path",
        'type: "guide"',
        "tags: [guide, learning, overview]",
        "---",
        "",
        "# 🎓 Block Reality 學習路徑",
        "",
        "> [!info] 給新加入的開發者與 Agent 的閱讀建議",
        "> 這是一條由淺入深的學習路徑，建議按照順序閱讀，不必一次讀完。",
        "",
        "## Phase 1: 架構與邊界（第 1 天）",
        "",
        "1. [[Architecture MOC]] — 了解模組邊界",
        "2. [[模組邊界與依賴方向]] — `fastdesign → api` 的單向依賴",
        "3. [[客戶端-伺服器端分離（極重要）]] — 這是 crash 的首要原因",
        "4. [[sigmaMax 正規化約定（PFSF 核心）]] — 最容易被忽略的數值約定",
        "",
        "## Phase 2: 核心物理引擎（第 2–3 天）",
        "",
        "1. [[PFSF 引擎總覽]] — 知道 PFSF 在做什麼",
        "2. [[PFSFDataBuilder 快速參考]] — 資料上傳的核心類別",
        "3. [[PFSF 物理計算完整資料流]] — 建立全局地圖",
        "4. [[CollapseManager 快速參考]] — 崩塌的生命周期",
        "5. [[RCFusionDetector 快速參考]] — RC 融合的設計哲學",
        "",
        "## Phase 3: 渲染管線（第 4 天）",
        "",
        "1. [[渲染系統總覽]] — Vulkan RT 的大局觀",
        "2. [[BRVulkanRT 快速參考]] — 渲染主控類別",
        "3. [[Vulkan RT 渲染管線資料流]] — 從 Chunk 到畫面的鏈路",
        "",
        "## Phase 4: 節點與 FastDesign（第 5 天）",
        "",
        "1. [[節點核心類別]] — BRNode / NodeGraph / EvaluateScheduler",
        "2. [[NodeRegistry 快速參考]] — 註冊表的職責與陷阱",
        "3. [[如何新增一個 FastDesign 節點]] — 標準開發模板",
        "4. [[如何安全地撰寫跨端網路封包]] — 避免 NoClassDefFoundError",
        "",
        "## Phase 5: ML 與工具鏈（第 6 天）",
        "",
        "1. [[OnnxPFSFRuntime 快速參考]] — 遊戲內 ML 推論入口",
        "2. [[ML 訓練到部署資料流]] — 從 Python 到 Java 的鏈路",
        "3. [[fix_imports.py — 修復 network 封包中的 client-only import]]",
        "4. [[常用 Gradle 任務速查]]",
        "",
        "## Phase 6: 錯誤排查與測試（持續參考）",
        "",
        "- [[NoClassDefFoundError-client-only 類別在伺服器端載入]]",
        "- [[PFSF GPU 求解發散或收斂極慢]]",
        "- [[Test Coverage MOC]] — 修改後該跑什麼測試",
        "",
        "## 🗺️ 視覺化導航",
        "",
        "如果你偏好圖形化閱讀，請打開 [[Block Reality 總覽.canvas]]，這是一張精心挑選的知識地圖。",
        "",
        "---",
        "",
        "返回 [[Home]] | 閱讀 [[Agent Guide]]",
    ]
    (essentials / "Learning Path.md").write_text("\n".join(learning_path), encoding="utf-8")

    # Copy AGENTS_GUIDE into vault
    agents_src = RAG_DIR / "AGENTS_GUIDE.md"
    if agents_src.exists():
        moc_dir = VAULT_DIR / "00 - MOC"
        moc_dir.mkdir(parents=True, exist_ok=True)
        text = agents_src.read_text(encoding="utf-8")
        # prepend frontmatter if not present
        if not text.startswith("---"):
            text = "---\nid: agent-guide\ntype: guide\ntags: [guide, agent]\n---\n\n" + text
        (moc_dir / "Agent Guide.md").write_text(text, encoding="utf-8")

# ============================================================
# Obsidian config
# ============================================================

def create_obsidian_config():
    obs_dir = VAULT_DIR / ".obsidian"
    obs_dir.mkdir(parents=True, exist_ok=True)

    (obs_dir / "app.json").write_text(json.dumps({
        "alwaysUpdateLinks": True,
        "newFileLocation": "folder",
        "newFileFolderPath": "99 - Auto",
        "livePreview": True,
        "defaultViewMode": "source"
    }, indent=2), encoding="utf-8")

    (obs_dir / "graph.json").write_text(json.dumps({
        "collapse-filter": True,
        "search": "",
        "localJumps": 1,
        "localBacklinks": True,
        "localForelinks": True,
        "localInterlinks": True,
        "showTags": True,
        "showAttachments": False,
        "hideUnresolved": False,
        "showOrphans": True,
        "collapse-color-groups": False,
        "colorGroups": [
            {"query": "tag:#critical", "color": {"a": 1, "rgb": 16711680}},
            {"query": "tag:#pattern", "color": {"a": 1, "rgb": 65280}},
            {"query": "tag:#troubleshooting", "color": {"a": 1, "rgb": 16776960}},
            {"query": "tag:#test_coverage", "color": {"a": 1, "rgb": 65535}},
            {"query": "tag:#key-class", "color": {"a": 1, "rgb": 16711935}},
            {"query": "tag:#dataflow", "color": {"a": 1, "rgb": 255}},
        ],
        "collapse-display": True,
        "showArrow": True,
        "textFadeMultiplier": 0,
        "nodeSizeMultiplier": 1.3,
        "lineSizeMultiplier": 1.2,
        "collapse-forces": True,
        "centerStrength": 0.5,
        "repelStrength": 10,
        "linkStrength": 1,
        "linkDistance": 250,
        "scale": 0.8,
    }, indent=2), encoding="utf-8")

    (obs_dir / "appearance.json").write_text(json.dumps({
        "theme": "obsidian",
        "baseFontSize": 16,
        "cssTheme": ""
    }, indent=2), encoding="utf-8")

# ============================================================
# Main
# ============================================================

def main():
    import shutil
    if VAULT_DIR.exists():
        shutil.rmtree(VAULT_DIR)
    VAULT_DIR.mkdir(parents=True, exist_ok=True)

    print("[Obsidian Export] Collecting titles...")
    title_to_file = collect_all_titles()
    print(f"  Collected {len(title_to_file)} titles")

    print("[Obsidian Export] Exporting handwritten indices...")
    moc_hw = export_handwritten_indices(title_to_file)
    print(f"  Handwritten notes: {sum(len(v) for v in moc_hw.values())}")

    print("[Obsidian Export] Exporting auto chunks...")
    moc_auto = export_auto_chunks(title_to_file)
    print(f"  Auto classes: {len(moc_auto.get('99 - Auto/Classes', []))}")
    print(f"  Auto tests: {len(moc_auto.get('99 - Auto/Tests', []))}")
    print(f"  Auto shaders: {len(moc_auto.get('99 - Auto/Shaders', []))}")
    print(f"  Auto method owners: {len([k for k in moc_auto if k.startswith('99 - Auto/Methods/')])}")

    moc_all = {**moc_hw, **moc_auto}

    print("[Obsidian Export] Creating MOC pages...")
    create_moc_pages(moc_all)

    print("[Obsidian Export] Creating folder notes...")
    create_folder_notes(moc_all)

    print("[Obsidian Export] Creating canvas files...")
    create_canvas_files()

    print("[Obsidian Export] Creating curated master map...")
    create_curated_canvas()

    print("[Obsidian Export] Creating learning path & agent guide...")
    create_learning_path_and_guide()

    print("[Obsidian Export] Creating Obsidian config...")
    create_obsidian_config()

    print(f"[Obsidian Export] Done. Vault at: {VAULT_DIR}")
    print("  Open this folder as an Obsidian vault to browse.")

if __name__ == "__main__":
    main()
