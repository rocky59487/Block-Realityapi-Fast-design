import os
import re

EXTENSIONS = {'.java', '.cpp', '.h', '.hpp', '.c', '.py', '.ts', '.tsx', '.glsl', '.md'}
EXCLUDE_DIRS = {'.git', 'build', 'L1-kb', 'docs', 'node_modules', 'dist', 'out', '__pycache__', 'merged-resources'}
KEYWORDS = [r'todo', r'stub', r'fixme', r'placeholder', r'被註解掉', r'省略', r'佔位', r'未實作']

def search_files(root_dir):
    matches = []
    regex = re.compile('|'.join(KEYWORDS), re.IGNORECASE)

    for dirpath, dirnames, filenames in os.walk(root_dir):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]

        for filename in filenames:
            ext = os.path.splitext(filename)[1].lower()
            if ext in EXTENSIONS:
                filepath = os.path.join(dirpath, filename)
                try:
                    with open(filepath, 'r', encoding='utf-8') as f:
                        for line_num, line in enumerate(f, 1):
                            if regex.search(line):
                                matches.append(f"{filepath}:{line_num}: {line.strip()}")
                except Exception as e:
                    pass
    return matches

if __name__ == '__main__':
    results = search_files('.')
    with open('search_results.txt', 'w', encoding='utf-8') as f:
        for r in results:
            f.write(r + '\n')
