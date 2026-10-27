#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin_dict.txt from the rime-ice dictionaries, and en_words.txt.

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

EN_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"
EN_WORDS = int(os.environ.get("EN_WORDS", "40000"))

# The subtitle word list splits contractions (didn + t); put them back together.
# (contraction, word whose count it borrows, share of that count)
CONTRACTIONS = [
    ("don't", "don", 0.9), ("didn't", "didn", 1), ("doesn't", "doesn", 1), ("isn't", "isn", 1),
    ("wasn't", "wasn", 1), ("aren't", "aren", 1), ("weren't", "weren", 1), ("hasn't", "hasn", 1),
    ("haven't", "haven", 0.9), ("couldn't", "couldn", 1), ("wouldn't", "wouldn", 1), ("shouldn't", "shouldn", 1),
    ("can't", "can", 0.25), ("won't", "won", 0.8), ("ain't", "ain", 1),
    ("i'm", "i", 0.15), ("i'll", "i", 0.05), ("i've", "i", 0.04), ("i'd", "i", 0.03),
    ("it's", "it", 0.15), ("that's", "that", 0.2), ("what's", "what", 0.15), ("there's", "there", 0.15),
    ("let's", "let", 0.6), ("he's", "he", 0.1), ("she's", "she", 0.1), ("who's", "who", 0.1),
    ("you're", "you", 0.06), ("we're", "we", 0.1), ("they're", "they", 0.1),
    ("you'll", "you", 0.02), ("we'll", "we", 0.05), ("they'll", "they", 0.03), ("it'll", "it", 0.02),
    ("you've", "you", 0.02), ("we've", "we", 0.04), ("they've", "they", 0.03), ("you'd", "you", 0.015),
]
FRAGMENTS = {"didn", "doesn", "isn", "wasn", "aren", "weren", "hasn", "couldn", "wouldn", "shouldn", "ain",
             "re", "ll", "ve", "dont", "im", "didnt", "cant", "doesnt", "isnt", "wasnt", "wont", "youre",
             "thats", "whats", "ive", "theyre", "lets", "arent", "couldnt", "wouldnt", "shouldnt", "havent"}

#s a subtitle corpus barely has but people type all the time, in their usual spelling.
TECH = """OK app API AI LLM GPT ChatGPT OpenAI DeepSeek Claude Gemini Copilot GitHub GitLab git commit push pull merge
branch bug debug demo feature deadline DDL PPT PDF email Gmail iPhone iPad iOS Android MacBook
Linux Ubuntu Docker Kubernetes Python Java JavaScript TypeScript Kotlin Golang SQL MySQL HTML CSS JSON
YAML URL HTTP HTTPS API SDK CPU GPU NPU RAM SSD USB WiFi Bluetooth VPN NVIDIA CUDA PyTorch TensorFlow transformer token
prompt agent RAG embedding dataset benchmark paper arXiv LaTeX Markdown YouTube Twitter TikTok
Google Microsoft Tesla Netflix Spotify UI UX KPI OKR CEO CTO HR PM IPO ETF GDP CPI PMI""".split()

def english(out, cache):
    """English words by frequency (hermitdave/FrequencyWords, CC BY-SA 4.0, from OpenSubtitles):
    one "word count" per line, best first."""
    path = os.path.join(cache, "en_50k.txt")
    if not os.path.exists(path):
        urllib.request.urlretrieve(EN_URL, path)
    ok = re.compile(r"^[a-z]+$")
    counts = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if len(p) != 2 or not ok.match(p[0]): continue
            # single letters other than a and i are noise in subtitles
            if len(p[0]) == 1 and p[0] not in ("a", "i"): continue
            counts[p[0]] = int(p[1])
    for word, base, share in CONTRACTIONS:
        if base in counts: counts[word] = int(counts[base] * share)
    for w in FRAGMENTS: counts.pop(w, None)
    counts.pop("don", None)
    for w in TECH:
        # the app matches case-insensitively and shows this spelling
        c = max(counts.pop(w.lower(), 0), 5000)
        counts[w] = c
    best = sorted(counts.items(), key=lambda x: -x[1])[:EN_WORDS]
    with open(out, "w", encoding="utf-8") as o:
        for w, c in best: o.write(f"{w} {c}\n")
    print(f"wrote {len(best)} English words to {out}")

if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "app/src/main/assets/pinyin_dict.txt")
    cache = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "build/dict-cache")
    main(out, cache)
    english(os.path.join(os.path.dirname(out), "en_words.txt"), cache)
