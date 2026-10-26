#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin_dict.txt from the rime-ice dictionaries.

rime-ice (https://github.com/iDvel/rime-ice, GPL-3.0) is downloaded at build time and not stored
in this repository. Output format, one line per pinyin key:
    zhong'guo<TAB>中国,123 种果,4
"""
import collections, os, re, sys, urllib.request

BASE = "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/"
FILES = ["8105.dict.yaml", "base.dict.yaml"]
MAX_WORDS = int(os.environ.get("MAX_WORDS", "220000"))

def fetch(name, cache):
    path = os.path.join(cache, name)
    if not os.path.exists(path):
        urllib.request.urlretrieve(BASE + name, path)
    return path

def rows(path):
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not body:
                body = line.startswith("...")
                continue
            if not line.strip() or line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) < 2:
                continue
            w = int(p[2]) if len(p) > 2 and p[2].isdigit() else 1
            yield p[0], p[1], w

def main(out, cache):
    os.makedirs(cache, exist_ok=True)
    ok = re.compile(r"^[a-z]+( [a-z]+)*$")
    chars, words = [], []
    for name in FILES:
        for word, py, w in rows(fetch(name, cache)):
            if not ok.match(py) or len(py.split()) != len(word):
                continue
            (chars if len(word) == 1 else words).append((word, py.replace(" ", "'"), w))
    words.sort(key=lambda x: -x[2])
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    table = collections.defaultdict(dict)
    for word, key, w in chars + words[:MAX_WORDS]:
        table[key][word] = max(table[key].get(word, 0), w)
    with open(out, "w", encoding="utf-8") as f:
        for key in sorted(table):
            items = sorted(table[key].items(), key=lambda x: -x[1])
            f.write(key + "\t" + " ".join(f"{w},{c}" for w, c in items) + "\n")
    print(f"wrote {len(table)} keys to {out}")

if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "app/src/main/assets/pinyin_dict.txt")
    cache = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "build/dict-cache")
    main(out, cache)
