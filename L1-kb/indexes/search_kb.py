#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Block Reality RAG Knowledge Base Search CLI v2
增強搜索：同義詞擴展、類圖查詢、多索引整合、更高精度排名

用法:
    python rag_kb/search_kb.py <query> [options]
    python rag_kb/search_kb.py "PFSF sigmaMax" --top 10
    python rag_kb/search_kb.py "client-only renderer" --tag critical
    python rag_kb/search_kb.py "NodeRegistry" --type class
    python rag_kb/search_kb.py --graph-children BRNode
    python rag_kb/search_kb.py --graph-implements IFusionDetector
"""

import io
import json
import re
import sys
from pathlib import Path
from collections import Counter, defaultdict
from difflib import SequenceMatcher

RAG_DIR = Path(__file__).parent

def load_json(name: str) -> dict | list:
    path = RAG_DIR / name
    if not path.exists():
        return [] if name.endswith(".json") and "index_" not in name else {}
    return json.loads(path.read_text(encoding="utf-8"))

# ============================================================
# 同義詞擴展
# ============================================================

SYNONYMS = {
    "pfsf": ["potential", "field", "structure", "failure", "gpu", "vulkan", "compute"],
    "client": ["client-only", "clientonly", "render", "gui", "hud", "screen"],
    "server": ["dedicated", "dedicatedserver", "sidesafe"],
    "node": ["nodes", "brnode", "noderegistry", "port", "wire", "evaluate"],
    "packet": ["network", "sync", "message", "handler"],
    "shader": ["glsl", "compute", "comp", "frag", "rt", "raytracing"],
    "material": ["rmaterial", "defaultmaterial", "custommaterial", "blocktype"],
    "collapse": ["falling", "break", "failure", "destroy", "fragment"],
    "fluid": ["water", "pressure", "navier", "stokes", "hydro"],
    "onnx": ["ml", "inference", "surrogate", "runtime"],
    "test": ["junit", "testing", "assert"],
    "gradle": ["build", "mergedjar", "runclient", "wrapper"],
    "crash": ["error", "exception", "noclassdeffound", "illegalaccess"],
}

def get_expanded_tokens(query: str) -> set:
    tokens = set(re.findall(r"[a-z0-9_]+", query.lower()))
    expanded = set(tokens)
    for tok in tokens:
        for key, syns in SYNONYMS.items():
            if tok == key or tok in syns:
                expanded.add(key)
                expanded.update(syns)
    return expanded

def tokenize(text: str) -> set:
    return set(re.findall(r"[a-z0-9_]+", text.lower()))

# ============================================================
# 評分函數
# ============================================================

def score_chunk(chunk: dict, query_tokens: set, raw_query: str) -> float:
    text = chunk.get("search_text", "").lower()
    title = chunk.get("title", "").lower()
    tags = [t.lower() for t in chunk.get("tags", [])]
    ctype = chunk.get("type", "").lower()
    
    score = 0.0
    text_tokens = tokenize(text)
    
    # 1. 同義詞擴展匹配
    matched = query_tokens & text_tokens
    score += len(matched) * 1.2
    
    # 1.5 子串/前綴匹配（對 CamelCase 類名特別有用）
    for qt in query_tokens:
        for tt in text_tokens:
            if len(qt) >= 4 and (qt in tt or tt in qt):
                if qt != tt:
                    score += 0.4
    
    # 2. 完全標題包含（極高權重）
    rq = raw_query.lower()
    if rq in title:
        score += 20.0
    # 類名完全匹配加分更多
    if title.endswith("." + rq) or title == rq:
        score += 15.0
    
    # 3. 標題中的 token 匹配
    title_tokens = tokenize(title)
    for tok in query_tokens:
        if tok in title_tokens:
            score += 4.0
        # CamelCase 子串匹配在標題中權重更高
        for tt in title_tokens:
            if len(tok) >= 4 and tok in tt:
                score += 1.5
    
    # 4. tag 匹配
    for tok in query_tokens:
        if tok in tags:
            score += 3.0
    
    # 5. 類型相關 bonus
    if ctype in ("class", "py_class", "cpp_class"):
        score += 1.5
    
    # 6. 相似度 bonus
    score += SequenceMatcher(None, rq, title).ratio() * 3.0
    
    # 7. 手寫索引條目優先
    if ctype in ("architecture", "rule", "spi", "pfsf", "render", "node", "pyml", "pattern", "troubleshooting", "native", "build"):
        score += 2.0
    
    # 8. critical / warning 標籤額外 boost
    if "critical" in tags:
        score += 2.5
    if chunk.get("metadata", {}).get("warning"):
        score += 1.5
    
    return score

# ============================================================
# 載入所有 chunk（自動 + 手寫）
# ============================================================

def load_all_chunks() -> list:
    chunks = load_json("chunks.json")
    extra_files = [
        "index_architecture.json",
        "index_rules.json",
        "index_spi.json",
        "index_pfsf_physics.json",
        "index_rendering.json",
        "index_nodes.json",
        "index_python_ml.json",
        "index_patterns.json",
        "index_troubleshooting.json",
        "index_native.json",
        "index_gradle_build.json",
        "index_dataflow.json",
        "index_toolchain.json",
        "index_test_coverage.json",
        "index_key_classes.json",
    ]
    for extra_file in extra_files:
        p = RAG_DIR / extra_file
        if p.exists():
            data = json.loads(p.read_text(encoding="utf-8"))
            for entry in data.get("entries", []):
                chunks.append({
                    "id": entry.get("id", ""),
                    "type": entry.get("type", "knowledge"),
                    "title": entry.get("title", ""),
                    "path": entry.get("path", extra_file),
                    "summary": entry.get("content", "")[:500],
                    "tags": entry.get("tags", []),
                    "metadata": entry.get("metadata", {}),
                    "search_text": f"{entry.get('title', '')} {entry.get('content', '')} {' '.join(entry.get('tags', []))}",
                })
    return chunks

# ============================================================
# 類圖查詢
# ============================================================

def _normalize_class_name(name: str) -> str:
    return name.split(".")[-1] if "." in name else name

def _match_graph_target(edge_target: str, query: str) -> bool:
    return edge_target == query or _normalize_class_name(edge_target) == _normalize_class_name(query)

def graph_query(mode: str, class_name: str):
    graph = load_json("class_graph.json")
    nodes = graph.get("nodes", {})
    edges = graph.get("edges", [])
    
    results = []
    short_query = _normalize_class_name(class_name)
    
    if mode == "children":
        for edge in edges:
            if edge["rel"] == "extends" and _match_graph_target(edge["to"], class_name):
                child = edge["from"]
                info = nodes.get(child, {})
                results.append({
                    "id": f"graph:child:{child}",
                    "type": "graph_node",
                    "title": child,
                    "path": info.get("path", ""),
                    "summary": f"Extends {edge['to']}",
                    "tags": ["class-graph", "child"],
                    "metadata": info,
                    "search_text": child,
                })
    elif mode == "implements":
        for edge in edges:
            if edge["rel"] == "implements" and _match_graph_target(edge["to"], class_name):
                impl = edge["from"]
                info = nodes.get(impl, {})
                results.append({
                    "id": f"graph:impl:{impl}",
                    "type": "graph_node",
                    "title": impl,
                    "path": info.get("path", ""),
                    "summary": f"Implements {edge['to']}",
                    "tags": ["class-graph", "impl"],
                    "metadata": info,
                    "search_text": impl,
                })
    elif mode == "parents":
        # 先找精確匹配，再找短名匹配
        info = nodes.get(class_name, {})
        if not info:
            for k, v in nodes.items():
                if _normalize_class_name(k) == short_query:
                    info = v
                    break
        extends = info.get("extends")
        implements = info.get("implements", [])
        if extends:
            results.append({
                "id": f"graph:parent:{extends}",
                "type": "graph_node",
                "title": extends,
                "summary": f"Parent of {class_name}",
                "tags": ["class-graph", "parent"],
                "metadata": {},
                "search_text": extends,
            })
        for impl in implements:
            results.append({
                "id": f"graph:interface:{impl}",
                "type": "graph_node",
                "title": impl,
                "summary": f"Interface of {class_name}",
                "tags": ["class-graph", "interface"],
                "metadata": {},
                "search_text": impl,
            })
    return results

# ============================================================
# 搜尋核心
# ============================================================

def search(query: str, top_n: int = 10, tag_filter: str = None, type_filter: str = None):
    all_chunks = load_all_chunks()
    query_tokens = get_expanded_tokens(query)
    
    scored = []
    for chunk in all_chunks:
        if tag_filter and tag_filter.lower() not in [t.lower() for t in chunk.get("tags", [])]:
            continue
        if type_filter and chunk.get("type", "").lower() != type_filter.lower():
            continue
        s = score_chunk(chunk, query_tokens, query)
        if s > 0:
            scored.append((s, chunk))
    
    scored.sort(key=lambda x: (-x[0], x[1]["title"]))
    return scored[:top_n]

# ============================================================
# 格式化輸出
# ============================================================

def fmt_result(rank: int, score: float, chunk: dict) -> str:
    lines = [
        f"#{rank}  [score={score:.1f}]  {chunk['title']}",
        f"    type: {chunk['type']}  |  path: {chunk.get('path', 'N/A')}",
    ]
    if chunk.get("tags"):
        lines.append(f"    tags: {', '.join(chunk['tags'])}")
    if chunk.get("summary"):
        summary = chunk["summary"].replace("\n", " ")
        if len(summary) > 350:
            summary = summary[:350] + "..."
        lines.append(f"    summary: {summary}")
    md = chunk.get("metadata", {})
    if md.get("extends"):
        lines.append(f"    extends: {md['extends']}")
    if md.get("implements"):
        lines.append(f"    implements: {', '.join(md['implements'])}")
    if md.get("signature"):
        lines.append(f"    signature: {md['signature']}")
    if md.get("related_files"):
        lines.append(f"    related: {', '.join(md['related_files'])}")
    if md.get("warning"):
        lines.append(f"    [!] WARNING: {md['warning']}")
    if md.get("severity"):
        lines.append(f"    severity: {md['severity']}")
    lines.append("")
    return "\n".join(lines)

# ============================================================
# CLI
# ============================================================

def safe_print(text: str):
    try:
        print(text)
    except UnicodeEncodeError:
        print(text.encode("utf-8", "replace").decode("utf-8", "replace"))

def main():
    if sys.platform == "win32":
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    
    import argparse
    parser = argparse.ArgumentParser(description="Block Reality RAG KB Search v2")
    parser.add_argument("query", nargs="?", help="Search query")
    parser.add_argument("--top", type=int, default=10, help="Max results")
    parser.add_argument("--tag", help="Filter by tag")
    parser.add_argument("--type", help="Filter by chunk type")
    parser.add_argument("--list-tags", action="store_true", help="List common tags")
    parser.add_argument("--graph-children", help="Find classes extending given class")
    parser.add_argument("--graph-implements", help="Find classes implementing given interface")
    parser.add_argument("--graph-parents", help="Find parents/interfaces of given class")
    parser.add_argument("--methods-of", help="List methods of a given class (partial name match)")
    args = parser.parse_args()
    
    # 類圖查詢優先
    for mode, val in [("children", args.graph_children), ("implements", args.graph_implements), ("parents", args.graph_parents)]:
        if val:
            results = graph_query(mode, val)
            safe_print(f"Graph query [{mode}] for '{val}' ({len(results)} results):\n")
            for i, r in enumerate(results, 1):
                safe_print(fmt_result(i, 0.0, r))
            return
    
    if args.methods_of:
        chunks = load_all_chunks()
        query = args.methods_of.lower()
        matched = []
        for ch in chunks:
            if ch.get("type") == "method":
                owner = ch.get("metadata", {}).get("owner_class", "")
                if owner and (query in owner.lower() or owner.lower().endswith("." + query)):
                    matched.append(ch)
        safe_print(f"Methods of '{args.methods_of}' ({len(matched)} results, top {min(30, len(matched))}):\n")
        for i, ch in enumerate(matched[:30], 1):
            safe_print(fmt_result(i, 0.0, ch))
        return
    
    if args.list_tags:
        chunks = load_all_chunks()
        c = Counter()
        for ch in chunks:
            for t in ch.get("tags", []):
                c[t] += 1
        safe_print("Top tags:")
        for tag, cnt in c.most_common(40):
            safe_print(f"  {tag:24s} {cnt}")
        return
    
    if not args.query:
        parser.print_help()
        return
    
    results = search(args.query, args.top, args.tag, args.type)
    if not results:
        safe_print(f"No results for '{args.query}'")
        return
    
    safe_print(f"Results for '{args.query}' (top {len(results)}):\n")
    for i, (score, chunk) in enumerate(results, 1):
        safe_print(fmt_result(i, score, chunk))

if __name__ == "__main__":
    main()
