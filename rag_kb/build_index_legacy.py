#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Block Reality RAG Knowledge Base Builder
自動掃描專案結構並生成結構化檢索索引
"""

import os
import re
import json
import ast
from pathlib import Path
from collections import defaultdict
from datetime import datetime

PROJECT_ROOT = Path(__file__).parent.parent
RAG_DIR = PROJECT_ROOT / "rag_kb"

# ============================================================
# Java 源碼解析器
# ============================================================

def parse_java_file(filepath: Path) -> dict:
    """簡易 Java 類別/方法/註解提取器"""
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    result = {
        "path": rel_path,
        "package": None,
        "classes": [],
        "imports": [],
        "annotations": [],
        "is_client_only": False,
        "is_test": "test" in rel_path.lower(),
    }
    
    # package
    pkg_match = re.search(r"package\s+([\w.]+);", content)
    if pkg_match:
        result["package"] = pkg_match.group(1)
    
    # imports
    result["imports"] = re.findall(r"import\s+([\w.*]+);", content)
    
    # 檢查 client-only
    result["is_client_only"] = "@OnlyIn(Dist.CLIENT)" in content or "client/" in rel_path.lower()
    
    # 類別提取 (簡化版)
    class_pattern = re.compile(
        r"(?:(public|private|protected)\s+)?"
        r"(?:(abstract|final)\s+)?"
        r"(?:class|interface|enum|record)\s+"
        r"(\w+)"
        r"(?:\s*<[^>]+>)?"
        r"(?:\s+extends\s+([\w<>,.\s]+))?"
        r"(?:\s+implements\s+([\w<>,.\s]+))?"
    )
    
    for match in class_pattern.finditer(content):
        visibility, modifier, name, extends_cls, implements = match.groups()
        
        # 提取 Javadoc / 註解上方的描述
        start_pos = match.start()
        prefix = content[max(0, start_pos-800):start_pos]
        doc = ""
        javadoc = re.search(r"/\*\*(.*?)\*/", prefix, re.DOTALL)
        if javadoc:
            doc = " ".join(line.strip().replace("*", "").strip() for line in javadoc.group(1).splitlines())
        
        result["classes"].append({
            "name": name,
            "type": "class" if "class" in content[start_pos:start_pos+20] else "interface" if "interface" in content[start_pos:start_pos+20] else "enum",
            "visibility": visibility or "package",
            "extends": extends_cls.strip() if extends_cls else None,
            "implements": [i.strip() for i in implements.split(",") if i.strip()] if implements else [],
            "doc_summary": doc.strip()[:300],
        })
    
    return result

# ============================================================
# Python 源碼解析器
# ============================================================

def parse_python_file(filepath: Path) -> dict:
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    result = {
        "path": rel_path,
        "classes": [],
        "functions": [],
        "imports": [],
    }
    
    # 靜態 import 提取
    result["imports"] = re.findall(r"^(?:from\s+([\w.]+)\s+import|import\s+([\w.,\s]+))", content, re.MULTILINE)
    
    try:
        tree = ast.parse(content)
    except SyntaxError:
        return result
    
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            bases = [ast.unparse(b) for b in node.bases] if hasattr(ast, "unparse") else []
            doc = ast.get_docstring(node)
            result["classes"].append({
                "name": node.name,
                "bases": bases,
                "line": node.lineno,
                "doc": (doc or "")[:250],
            })
        elif isinstance(node, ast.FunctionDef):
            doc = ast.get_docstring(node)
            result["functions"].append({
                "name": node.name,
                "line": node.lineno,
                "doc": (doc or "")[:200],
            })
    
    return result

# ============================================================
# C++ 源碼解析器 (簡化)
# ============================================================

def parse_cpp_file(filepath: Path) -> dict:
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    classes = []
    # class / struct
    for m in re.finditer(r"\b(class|struct)\s+(\w+)(?:\s*:\s*(?:public|private|protected)\s+([\w<>:,\s]+))?,?", content):
        kind, name, bases = m.groups()
        classes.append({
            "name": name,
            "kind": kind,
            "bases": [b.strip() for b in bases.split(",")] if bases else [],
        })
    
    return {
        "path": rel_path,
        "classes": classes,
    }

# ============================================================
# 掃描專案
# ============================================================

def scan_project():
    java_api = []
    java_fd = []
    java_tests = []
    python = []
    cpp = []
    docs = []
    shaders = []
    
    # Java API
    for f in sorted((PROJECT_ROOT / "Block Reality" / "api" / "src").rglob("*.java")):
        info = parse_java_file(f)
        if info["is_test"]:
            java_tests.append(info)
        else:
            java_api.append(info)
    
    # Java FastDesign
    for f in sorted((PROJECT_ROOT / "Block Reality" / "fastdesign" / "src").rglob("*.java")):
        info = parse_java_file(f)
        if info["is_test"]:
            java_tests.append(info)
        else:
            java_fd.append(info)
    
    # Python
    for f in sorted((PROJECT_ROOT / "brml" / "brml").rglob("*.py")):
        python.append(parse_python_file(f))
    
    # C++
    for f in sorted((PROJECT_ROOT / "libpfsf" / "src").rglob("*.*")):
        if f.suffix in {".cpp", ".h", ".hpp", ".cc"}:
            cpp.append(parse_cpp_file(f))
    for f in sorted((PROJECT_ROOT / "libpfsf" / "include").rglob("*.*")):
        if f.suffix in {".h", ".hpp"}:
            cpp.append(parse_cpp_file(f))
    
    # Docs
    for f in sorted((PROJECT_ROOT / "docs").rglob("*.md")):
        docs.append(str(f.relative_to(PROJECT_ROOT)).replace("\\", "/"))
    
    # GLSL shaders
    for root in [PROJECT_ROOT / "Block Reality" / "api" / "src" / "main" / "resources"]:
        for f in sorted(root.rglob("*.glsl")):
            shaders.append(str(f.relative_to(PROJECT_ROOT)).replace("\\", "/"))
    
    return {
        "java_api": java_api,
        "java_fastdesign": java_fd,
        "java_tests": java_tests,
        "python": python,
        "cpp": cpp,
        "docs": docs,
        "shaders": shaders,
        "generated_at": datetime.now().isoformat(),
    }

# ============================================================
# 產生面向 RAG 的 Chunk 索引
# ============================================================

def build_chunks(raw_data: dict) -> list:
    chunks = []
    
    # --- Java API chunks ---
    for file_info in raw_data["java_api"]:
        pkg = file_info["package"]
        path = file_info["path"]
        for cls in file_info["classes"]:
            fqcn = f"{pkg}.{cls['name']}" if pkg else cls["name"]
            tags = ["java", "api", pkg.split(".")[-1] if pkg else "unknown"]
            if file_info["is_client_only"]:
                tags.append("client-only")
            
            chunks.append({
                "id": f"java_api:{fqcn}",
                "type": "class",
                "title": fqcn,
                "path": path,
                "summary": cls["doc_summary"] or f"{cls['type'].title()} {fqcn}",
                "tags": tags,
                "metadata": {
                    "extends": cls["extends"],
                    "implements": cls["implements"],
                    "visibility": cls["visibility"],
                },
                "search_text": f"{fqcn} {cls['doc_summary']} {' '.join(tags)}",
            })
    
    # --- Java FastDesign chunks ---
    for file_info in raw_data["java_fastdesign"]:
        pkg = file_info["package"]
        path = file_info["path"]
        for cls in file_info["classes"]:
            fqcn = f"{pkg}.{cls['name']}" if pkg else cls["name"]
            tags = ["java", "fastdesign", pkg.split(".")[-1] if pkg else "unknown"]
            if file_info["is_client_only"]:
                tags.append("client-only")
            if "node" in path.lower():
                tags.append("node")
            
            chunks.append({
                "id": f"java_fd:{fqcn}",
                "type": "class",
                "title": fqcn,
                "path": path,
                "summary": cls["doc_summary"] or f"{cls['type'].title()} {fqcn} in fastdesign",
                "tags": tags,
                "metadata": {
                    "extends": cls["extends"],
                    "implements": cls["implements"],
                    "visibility": cls["visibility"],
                },
                "search_text": f"{fqcn} {cls['doc_summary']} {' '.join(tags)}",
            })
    
    # --- Python chunks ---
    for file_info in raw_data["python"]:
        path = file_info["path"]
        mod_name = path.replace("/", ".").replace(".py", "")
        for cls in file_info["classes"]:
            tags = ["python", "ml", mod_name.split(".")[-2] if "." in mod_name else "brml"]
            chunks.append({
                "id": f"py:{mod_name}.{cls['name']}",
                "type": "py_class",
                "title": f"{mod_name}.{cls['name']}",
                "path": path,
                "summary": cls["doc"] or f"Python class {cls['name']}",
                "tags": tags,
                "metadata": {"line": cls["line"], "bases": cls["bases"]},
                "search_text": f"{cls['name']} {cls['doc']} {' '.join(tags)}",
            })
        for func in file_info["functions"]:
            if func["name"].startswith("_"):
                continue
            chunks.append({
                "id": f"py:{mod_name}.{func['name']}",
                "type": "py_function",
                "title": f"{mod_name}.{func['name']}",
                "path": path,
                "summary": func["doc"] or f"Function {func['name']}",
                "tags": ["python", "ml"],
                "metadata": {"line": func["line"]},
                "search_text": f"{func['name']} {func['doc']} python ml",
            })
    
    # --- C++ chunks ---
    for file_info in raw_data["cpp"]:
        path = file_info["path"]
        for cls in file_info["classes"]:
            chunks.append({
                "id": f"cpp:{path}#{cls['name']}",
                "type": "cpp_class",
                "title": cls["name"],
                "path": path,
                "summary": f"C++ {cls['kind']} {cls['name']}",
                "tags": ["cpp", "pfsf", "solver"],
                "metadata": {"bases": cls["bases"]},
                "search_text": f"{cls['name']} cpp pfsf solver",
            })
    
    # --- Doc chunks ---
    for doc_path in raw_data["docs"]:
        chunks.append({
            "id": f"doc:{doc_path}",
            "type": "doc",
            "title": doc_path.split("/")[-1],
            "path": doc_path,
            "summary": f"Documentation file: {doc_path}",
            "tags": ["doc"],
            "metadata": {},
            "search_text": doc_path,
        })
    
    return chunks

# ============================================================
# 產生反向索引與統計
# ============================================================

def build_inverted_index(chunks: list) -> dict:
    index = defaultdict(list)
    for chunk in chunks:
        text = chunk["search_text"].lower()
        # 簡單分詞
        tokens = set(re.findall(r"[a-z0-9_]+", text))
        for tok in tokens:
            if len(tok) > 2:
                index[tok].append(chunk["id"])
    return dict(index)

# ============================================================
# Main
# ============================================================

def main():
    print("[RAG Builder] Scanning project...")
    raw = scan_project()
    
    print(f"  Java API files: {len(raw['java_api'])}")
    print(f"  Java FastDesign files: {len(raw['java_fastdesign'])}")
    print(f"  Java Test files: {len(raw['java_tests'])}")
    print(f"  Python files: {len(raw['python'])}")
    print(f"  C++ files: {len(raw['cpp'])}")
    print(f"  Doc files: {len(raw['docs'])}")
    
    print("[RAG Builder] Building chunks...")
    chunks = build_chunks(raw)
    print(f"  Total chunks: {len(chunks)}")
    
    print("[RAG Builder] Building inverted index...")
    inv_index = build_inverted_index(chunks)
    
    # 存檔
    (RAG_DIR / "raw_scan.json").write_text(
        json.dumps(raw, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (RAG_DIR / "chunks.json").write_text(
        json.dumps(chunks, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (RAG_DIR / "inverted_index.json").write_text(
        json.dumps(inv_index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    
    print("[RAG Builder] Done.")
    print(f"  Output: {RAG_DIR}/chunks.json ({len(chunks)} chunks)")
    print(f"  Output: {RAG_DIR}/inverted_index.json ({len(inv_index)} tokens)")

if __name__ == "__main__":
    main()
