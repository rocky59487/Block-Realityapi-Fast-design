#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Block Reality RAG Knowledge Base Builder v2
增強版：提取方法簽名、字段、GLSL shader、Doc 摘要、類關係圖
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
# Java 源碼解析器 v2
# ============================================================

def parse_java_file(filepath: Path) -> dict:
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    result = {
        "path": rel_path,
        "package": None,
        "classes": [],
        "methods": [],
        "fields": [],
        "imports": [],
        "annotations": [],
        "is_client_only": False,
        "is_test": "test" in rel_path.lower(),
    }
    
    pkg_match = re.search(r"package\s+([\w.]+);", content)
    if pkg_match:
        result["package"] = pkg_match.group(1)
    
    result["imports"] = re.findall(r"import\s+([\w.*]+);", content)
    result["is_client_only"] = "@OnlyIn(Dist.CLIENT)" in content or "client/" in rel_path.lower()
    
    # 類別提取
    class_pattern = re.compile(
        r"(?:(public|private|protected)\s+)?"
        r"(?:(abstract|final|static)\s+)*"
        r"(?:class|interface|enum|record)\s+"
        r"(\w+)"
        r"(?:\s*<[^>]+>)?"
        r"(?:\s+extends\s+([\w<>,.\s]+))?"
        r"(?:\s+implements\s+([\w<>,.\s]+))?"
    )
    
    classes_found = []
    for match in class_pattern.finditer(content):
        visibility = match.group(1)
        modifier = match.group(2)
        name = match.group(3)
        extends_cls = match.group(4)
        implements = match.group(5)
        
        start_pos = match.start()
        prefix = content[max(0, start_pos-800):start_pos]
        doc = ""
        javadoc = re.search(r"/\*\*(.*?)\*/", prefix, re.DOTALL)
        if javadoc:
            doc = " ".join(line.strip().replace("*", "").strip() for line in javadoc.group(1).splitlines())
        
        cls_info = {
            "name": name,
            "type": "class" if "class" in content[start_pos:start_pos+20] else "interface" if "interface" in content[start_pos:start_pos+20] else "enum",
            "visibility": visibility or "package",
            "extends": extends_cls.strip() if extends_cls else None,
            "implements": [i.strip() for i in implements.split(",") if i.strip()] if implements else [],
            "doc_summary": doc.strip()[:400],
        }
        classes_found.append((name, match.end()))
        result["classes"].append(cls_info)
    
    # 方法提取（簡化但夠用）
    method_pattern = re.compile(
        r"(?:(public|private|protected)\s+)?"
        r"(?:(static|final|abstract|synchronized|native)\s+)*"
        r"(?:<[^>]+>\s+)?"
        r"([\w<>,.?\[\]\s]+?)\s+"
        r"(\w+)\s*\(\s*([^)]*?)\s*\)"
        r"(?:\s*\{|;|throws\s+[\w,\s]+)"
    )
    
    # 估算每個方法屬於哪個類別
    for m in method_pattern.finditer(content):
        vis, mods, ret_type, mname, params = m.groups()
        if not ret_type or not mname:
            continue
        # 過濾掉明顯不是方法的東西（如 if/while/for）
        if mname in ("if", "while", "for", "switch", "catch", "synchronized"):
            continue
        # 過濾掉類別宣告被誤認
        if ret_type.strip() in ("class", "interface", "enum", "record"):
            continue
        ret_clean = ret_type.strip()
        # 簡單排除構造函數以外的類別名（ret_clean 看起來像類別名但沒有空格）
        if not ret_clean or not mname:
            continue
        if " " not in ret_clean and ret_clean[0].isupper() and mname[0].isupper():
            continue
        
        pos = m.start()
        owner_class = None
        for cls_name, cls_end in classes_found:
            if pos > cls_end:
                owner_class = cls_name
        
        param_list = [p.strip() for p in params.split(",") if p.strip()]
        result["methods"].append({
            "name": mname,
            "return_type": ret_clean,
            "params": param_list,
            "visibility": vis or "package",
            "owner_class": owner_class,
        })
    
    # 字段提取
    field_pattern = re.compile(
        r"(?:(public|private|protected)\s+)?"
        r"(?:(static|final|volatile|transient)\s+)*"
        r"([\w<>,.?\[\]\s]+?)\s+"
        r"(\w+)\s*(?:=|;)"
    )
    for f in field_pattern.finditer(content):
        vis, mods, ftype, fname = f.groups()
        if not ftype or not fname:
            continue
        if fname in ("if", "for", "while", "return"):
            continue
        ftype_clean = ftype.strip()
        if ftype_clean in ("class", "interface", "enum"):
            continue
        pos = f.start()
        owner_class = None
        for cls_name, cls_end in classes_found:
            if pos > cls_end:
                owner_class = cls_name
        result["fields"].append({
            "name": fname,
            "type": ftype_clean,
            "visibility": vis or "package",
            "owner_class": owner_class,
        })
    
    return result

# ============================================================
# Python 源碼解析器 v2
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
    
    result["imports"] = re.findall(r"^(?:from\s+([\w.]+)\s+import|import\s+([\w.,\s]+))", content, re.MULTILINE)
    
    try:
        tree = ast.parse(content)
    except SyntaxError:
        return result
    
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            bases = [ast.unparse(b) for b in node.bases] if hasattr(ast, "unparse") else []
            doc = ast.get_docstring(node)
            methods = []
            for child in node.body:
                if isinstance(child, ast.FunctionDef):
                    mdoc = ast.get_docstring(child)
                    # 提取參數
                    args = []
                    for arg in child.args.args:
                        arg_type = ""
                        if arg.annotation and hasattr(ast, "unparse"):
                            arg_type = ast.unparse(arg.annotation)
                        args.append(f"{arg.arg}:{arg_type}" if arg_type else arg.arg)
                    methods.append({
                        "name": child.name,
                        "args": args,
                        "doc": (mdoc or "")[:200],
                    })
            result["classes"].append({
                "name": node.name,
                "bases": bases,
                "line": node.lineno,
                "doc": (doc or "")[:300],
                "methods": methods,
            })
        elif isinstance(node, ast.FunctionDef) and not isinstance(node, ast.AsyncFunctionDef):
            # 只取模組級函數
            if not any(isinstance(p, ast.ClassDef) and node.lineno > p.lineno and node.lineno <= p.end_lineno for p in ast.walk(tree) if isinstance(p, ast.ClassDef)):
                doc = ast.get_docstring(node)
                args = []
                for arg in node.args.args:
                    arg_type = ""
                    if arg.annotation and hasattr(ast, "unparse"):
                        arg_type = ast.unparse(arg.annotation)
                    args.append(f"{arg.arg}:{arg_type}" if arg_type else arg.arg)
                result["functions"].append({
                    "name": node.name,
                    "line": node.lineno,
                    "doc": (doc or "")[:200],
                    "args": args,
                })
    
    return result

# ============================================================
# C++ 源碼解析器 (簡化)
# ============================================================

def parse_cpp_file(filepath: Path) -> dict:
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    classes = []
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
# GLSL Shader 解析器
# ============================================================

def parse_glsl_file(filepath: Path) -> dict:
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    # 提取 layout 與 uniform
    layouts = re.findall(r"layout\s*\([^)]*\)\s+(\w+)\s+(\w+);", content)
    uniforms = re.findall(r"uniform\s+(\w+)\s+(\w+)(?:\[[^\]]*\])?;", content)
    functions = re.findall(r"(?:void|[\w]+)\s+(\w+)\s*\([^)]*\)\s*\{", content)
    
    # 提取前 5 行註解作為摘要
    comment_lines = []
    for line in content.splitlines()[:30]:
        line = line.strip()
        if line.startswith("//") or line.startswith("/*"):
            comment_lines.append(line.lstrip("/").lstrip("*").strip())
        elif line.startswith("#") or line.startswith("layout") or line.startswith("void"):
            break
    
    return {
        "path": rel_path,
        "layouts": layouts,
        "uniforms": uniforms,
        "functions": functions,
        "comment_summary": " ".join(comment_lines)[:300],
    }

# ============================================================
# Doc Markdown 摘要提取
# ============================================================

def parse_doc_file(filepath: Path) -> list:
    """將 Markdown 按 heading 分段，每段成為一個 doc chunk"""
    content = filepath.read_text(encoding="utf-8", errors="ignore")
    rel_path = str(filepath.relative_to(PROJECT_ROOT)).replace("\\", "/")
    
    # 提取文件級標題
    doc_title = ""
    h1 = re.search(r"^#\s+(.+)$", content, re.MULTILINE)
    if h1:
        doc_title = h1.group(1).strip()
    
    chunks = []
    # 按 heading 分割 (H2/H3/H4)
    # 模式：行首為 ## ### #### 且後面有內容
    section_pattern = re.compile(r"^(#{2,4})\s+(.+)$", re.MULTILINE)
    
    # 先處理文件開頭（H1 之後到第一個 H2 之間）
    first_h2 = section_pattern.search(content)
    if first_h2:
        preamble = content[:first_h2.start()].strip()
        # 移除 H1
        preamble = re.sub(r"^#\s+.+$", "", preamble, flags=re.MULTILINE).strip()
        if preamble:
            preamble_clean = re.sub(r"\s+", " ", preamble)[:500]
            chunks.append({
                "path": rel_path,
                "section": "(overview)",
                "title": doc_title or Path(rel_path).name,
                "summary": preamble_clean,
            })
    
    # 遍歷每個 section
    for m in section_pattern.finditer(content):
        level = len(m.group(1))
        heading = m.group(2).strip()
        start = m.end()
        # 找下一個同級或更高級 heading
        next_match = section_pattern.search(content, start)
        end = next_match.start() if next_match else len(content)
        body = content[start:end].strip()
        
        # 清理 body：移除子標題但保留其下的文字，只取前 N 字
        body_clean = re.sub(r"\n+", " ", body)
        body_clean = re.sub(r"\s+", " ", body_clean)[:600]
        
        chunks.append({
            "path": rel_path,
            "section": heading,
            "title": f"{doc_title or Path(rel_path).name} — {heading}",
            "summary": body_clean,
        })
    
    # 如果完全沒有 heading，就整份當一個 chunk
    if not chunks:
        summary = re.sub(r"\s+", " ", content)[:500]
        chunks.append({
            "path": rel_path,
            "section": "(full)",
            "title": doc_title or Path(rel_path).name,
            "summary": summary,
        })
    
    return chunks

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
    
    # Docs (heading-based chunking)
    for f in sorted((PROJECT_ROOT / "docs").rglob("*.md")):
        docs.extend(parse_doc_file(f))
    
    # GLSL shaders
    for root in [PROJECT_ROOT / "Block Reality" / "api" / "src" / "main" / "resources"]:
        for f in sorted(root.rglob("*.glsl")):
            shaders.append(parse_glsl_file(f))
    
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
# 產生 Chunk 索引
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
        
        for method in file_info["methods"]:
            owner = f"{pkg}.{method['owner_class']}" if pkg and method['owner_class'] else method['owner_class']
            sig = f"{method['return_type']} {method['name']}({', '.join(method['params'][:3])}{'...' if len(method['params'])>3 else ''})"
            chunks.append({
                "id": f"java_api:{owner}#{method['name']}",
                "type": "method",
                "title": f"{owner}.{method['name']}",
                "path": path,
                "summary": f"Method: {sig}",
                "tags": ["java", "api", "method"],
                "metadata": {
                    "owner_class": owner,
                    "signature": sig,
                    "visibility": method["visibility"],
                },
                "search_text": f"{owner}.{method['name']} {sig} java api method",
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
        
        for method in file_info["methods"]:
            owner = f"{pkg}.{method['owner_class']}" if pkg and method['owner_class'] else method['owner_class']
            sig = f"{method['return_type']} {method['name']}({', '.join(method['params'][:3])}{'...' if len(method['params'])>3 else ''})"
            chunks.append({
                "id": f"java_fd:{owner}#{method['name']}",
                "type": "method",
                "title": f"{owner}.{method['name']}",
                "path": path,
                "summary": f"Method: {sig}",
                "tags": ["java", "fastdesign", "method"],
                "metadata": {
                    "owner_class": owner,
                    "signature": sig,
                    "visibility": method["visibility"],
                },
                "search_text": f"{owner}.{method['name']} {sig} java fastdesign method",
            })
    
    # --- Java Test chunks ---
    for file_info in raw_data["java_tests"]:
        pkg = file_info["package"]
        path = file_info["path"]
        for cls in file_info["classes"]:
            fqcn = f"{pkg}.{cls['name']}" if pkg else cls["name"]
            chunks.append({
                "id": f"java_test:{fqcn}",
                "type": "test",
                "title": fqcn,
                "path": path,
                "summary": cls["doc_summary"] or f"Test {fqcn}",
                "tags": ["java", "test", "junit"],
                "metadata": {"extends": cls["extends"]},
                "search_text": f"{fqcn} test junit",
            })
    
    # --- Python chunks ---
    for file_info in raw_data["python"]:
        path = file_info["path"]
        mod_name = path.replace("/", ".").replace(".py", "")
        for cls in file_info["classes"]:
            tags = ["python", "ml", mod_name.split(".")[-2] if "." in mod_name else "brml"]
            method_names = [m["name"] for m in cls.get("methods", [])]
            chunks.append({
                "id": f"py:{mod_name}.{cls['name']}",
                "type": "py_class",
                "title": f"{mod_name}.{cls['name']}",
                "path": path,
                "summary": cls["doc"] or f"Python class {cls['name']}",
                "tags": tags,
                "metadata": {
                    "line": cls["line"],
                    "bases": cls["bases"],
                    "methods": method_names[:10],
                },
                "search_text": f"{cls['name']} {cls['doc']} {' '.join(method_names)} {' '.join(tags)}",
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
                "metadata": {
                    "line": func["line"],
                    "args": func.get("args", []),
                },
                "search_text": f"{func['name']} {func['doc']} python ml {mod_name}",
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
    
    # --- Doc chunks (heading-based) ---
    for doc_info in raw_data["docs"]:
        chunks.append({
            "id": f"doc:{doc_info['path']}#{doc_info['section']}",
            "type": "doc",
            "title": doc_info["title"],
            "path": doc_info["path"],
            "summary": doc_info["summary"] or f"Documentation: {doc_info['path']}",
            "tags": ["doc", "markdown"],
            "metadata": {"filename": Path(doc_info["path"]).name, "section": doc_info["section"]},
            "search_text": f"{doc_info['title']} {doc_info['summary']} {doc_info['path']}",
        })
    
    # --- Shader chunks ---
    for shader_info in raw_data["shaders"]:
        path = shader_info["path"]
        stage = Path(path).suffix.replace(".", "")
        funcs = shader_info.get("functions", [])
        chunks.append({
            "id": f"shader:{path}",
            "type": "shader",
            "title": Path(path).name,
            "path": path,
            "summary": shader_info["comment_summary"] or f"GLSL {stage} shader",
            "tags": ["shader", "glsl", stage],
            "metadata": {
                "functions": funcs,
                "uniforms": [u[1] for u in shader_info.get("uniforms", [])],
            },
            "search_text": f"{Path(path).name} {' '.join(funcs)} glsl shader {stage} {shader_info['comment_summary']}",
        })
    
    return chunks

# ============================================================
# 產生類別關係圖索引
# ============================================================

def build_class_graph(raw_data: dict) -> dict:
    """建立繼承、實作、引用關係圖"""
    nodes = {}
    edges = []
    
    def add_file_infos(file_infos, module_tag):
        for file_info in file_infos:
            pkg = file_info.get("package", "")
            for cls in file_info.get("classes", []):
                fqcn = f"{pkg}.{cls['name']}" if pkg else cls["name"]
                nodes[fqcn] = {
                    "module": module_tag,
                    "path": file_info["path"],
                    "type": cls["type"],
                    "extends": cls["extends"],
                    "implements": cls["implements"],
                }
                if cls["extends"]:
                    edges.append({"from": fqcn, "to": cls["extends"], "rel": "extends"})
                for impl in cls["implements"]:
                    edges.append({"from": fqcn, "to": impl, "rel": "implements"})
    
    add_file_infos(raw_data["java_api"], "api")
    add_file_infos(raw_data["java_fastdesign"], "fastdesign")
    add_file_infos(raw_data["java_tests"], "test")
    
    return {"nodes": nodes, "edges": edges}

# ============================================================
# 產生反向索引
# ============================================================

def build_inverted_index(chunks: list) -> dict:
    index = defaultdict(list)
    for chunk in chunks:
        text = chunk.get("search_text", "").lower()
        tokens = set(re.findall(r"[a-z0-9_]+", text))
        for tok in tokens:
            if len(tok) > 2:
                index[tok].append(chunk["id"])
    return dict(index)

# ============================================================
# Main
# ============================================================

def main():
    print("[RAG Builder v2] Scanning project...")
    raw = scan_project()
    
    print(f"  Java API files: {len(raw['java_api'])}")
    print(f"  Java FastDesign files: {len(raw['java_fastdesign'])}")
    print(f"  Java Test files: {len(raw['java_tests'])}")
    print(f"  Python files: {len(raw['python'])}")
    print(f"  C++ files: {len(raw['cpp'])}")
    print(f"  Doc files: {len(raw['docs'])}")
    print(f"  Shader files: {len(raw['shaders'])}")
    
    print("[RAG Builder v2] Building chunks...")
    chunks = build_chunks(raw)
    print(f"  Total chunks: {len(chunks)}")
    
    print("[RAG Builder v2] Building class graph...")
    class_graph = build_class_graph(raw)
    print(f"  Graph nodes: {len(class_graph['nodes'])}, edges: {len(class_graph['edges'])}")
    
    print("[RAG Builder v2] Building inverted index...")
    inv_index = build_inverted_index(chunks)
    
    # 存檔
    (RAG_DIR / "raw_scan.json").write_text(
        json.dumps(raw, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (RAG_DIR / "chunks.json").write_text(
        json.dumps(chunks, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (RAG_DIR / "class_graph.json").write_text(
        json.dumps(class_graph, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (RAG_DIR / "inverted_index.json").write_text(
        json.dumps(inv_index, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    
    print("[RAG Builder v2] Done.")
    print(f"  Output: {RAG_DIR}/chunks.json ({len(chunks)} chunks)")
    print(f"  Output: {RAG_DIR}/class_graph.json ({len(class_graph['nodes'])} nodes)")
    print(f"  Output: {RAG_DIR}/inverted_index.json ({len(inv_index)} tokens)")

if __name__ == "__main__":
    main()
