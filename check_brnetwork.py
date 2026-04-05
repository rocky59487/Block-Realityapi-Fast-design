import os
import re

file_path = 'Block Reality/api/src/main/java/com/blockreality/api/network/BRNetwork.java'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

print(content)
