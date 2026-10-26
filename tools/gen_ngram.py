#!/usr/bin/env python3
"""Builds zh_bigram.txt: which word tends to follow which, for 联想 and whole-sentence guessing.

Sources (downloaded, never stored in this repository), each normalized to the same size and summed:
  - libpinyin model19 (GPL-3.0+): a word bigram model from a large written corpus
  - OpenSubtitles (OPUS): film and TV dialogue, i.e. spoken everyday Chinese
  - LCCC (thu-coai, MIT): cleaned Weibo conversations
Only words present in the pinyin dictionary are kept. Output, one line per first word:
    今天<TAB>total<TAB>的,1365 是,115 ...
Usage: gen_ngram.py pinyin_dict.txt out.txt [cache_dir]
"""
import collections, gzip, io, json, math, os, re, sys, tarfile, urllib.request

TOP = int(os.environ.get("NGRAM_TOP", "40"))
MIN = float(os.environ.get("NGRAM_MIN", "3"))
LINES = int(os.environ.get("NGRAM_LINES", "4000000"))
WEIGHTS = {"libpinyin": 1.0, "subtitles": 1.0, "lccc": 0.8}

HAN = re.compile(r"[㐀-鿿]+")

def log(*a):
    print(*a, file=sys.stderr, flush=True)

def load_words(path):
    words = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            _, v = line.rstrip("\n").split("\t")
            for p in v.split(" "): words.add(p.rsplit(",", 1)[0])
    return words

def log_probs(freq):
    total = sum(freq.values()) + len(freq)
    return {w: math.log((c + 1) / total) for w, c in freq.items()}

class Segmenter:
    """Most-probable segmentation under a unigram model over the dictionary words."""
    def __init__(self, logp):
        self.logp = logp
        self.maxlen = 6
        self.unk = min(logp.values()) - 5
    def __call__(self, s):
        n = len(s)
        best = [0.0] + [-1e18] * n
        back = [0] * (n + 1)
        lp = self.logp
        for i in range(1, n + 1):
            bi = -1e18; bk = 1
            for k in range(1, min(i, self.maxlen) + 1):
                w = s[i - k:i]
                p = lp.get(w)
                if p is None:
                    if k > 1: continue
                    p = self.unk
                v = best[i - k] + p
                if v > bi: bi = v; bk = k
            best[i] = bi; back[i] = bk
        out = []
        i = n
        while i > 0:
            out.append(s[i - back[i]:i]); i -= back[i]
        out.reverse()
        return out

def count_text(lines, seg, words, limit):
    big = collections.Counter()
    n = 0
    for line in lines:
        for run in HAN.findall(line):
            toks = seg(run)
            for a, b in zip(toks, toks[1:]):
                if a in words and b in words: big[(a, b)] += 1
        n += 1
        if n % 500000 == 0: log(f"  {n} lines, {len(big)} pairs")
        if n >= limit: break
    return big

def open_url(url):
    req = urllib.request.Request(url, headers={"User-Agent": "TypeSome-dict-builder"})
    return urllib.request.urlopen(req, timeout=120)

def fetch(url, cache):
    path = os.path.join(cache, url.rsplit("/", 1)[-1])
    if not os.path.exists(path):
        log("download", url)
        with open_url(url) as r, open(path + ".part", "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b: break
                f.write(b)
        os.rename(path + ".part", path)
    return path

def libpinyin(cache, words):
    """Bigram counts, plus unigram counts over libpinyin's word-level lexicon (used for segmenting)."""
    path = fetch("https://downloads.sourceforge.net/libpinyin/model19.text.tar.gz", cache)
    big, uni = collections.Counter(), collections.Counter()
    with tarfile.open(path) as t:
        f = io.TextIOWrapper(t.extractfile("interpolation2.text"), encoding="utf-8")
        two = False
        for line in f:
            if line.startswith("\\2-gram"): two = True; continue
            if not line.startswith("\\item"): continue
            p = line.split()
            if not two: uni[p[2]] += int(p[4])
            elif p[2] in words and p[4] in words: big[(p[2], p[4])] += int(p[6])
    return big, uni

def subtitles(cache, words, seg):
    for url in ["https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/zh_cn.txt.gz",
                "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2016/mono/zh_cn.txt.gz"]:
        try:
            r = open_url(url)
        except Exception as e:
            log("  unavailable:", url, e); continue
        log("stream", url)
        with r:
            lines = io.TextIOWrapper(gzip.GzipFile(fileobj=r), encoding="utf-8", errors="ignore")
            return count_text((l.replace(" ", "") for l in lines), seg, words, LINES)
    return collections.Counter()

def strings(x):
    if isinstance(x, str): yield x
    elif isinstance(x, list):
        for y in x: yield from strings(y)
    elif isinstance(x, dict):
        for y in x.values(): yield from strings(y)

def lccc(cache, words, seg):
    try:
        with open_url("https://huggingface.co/api/datasets/thu-coai/lccc") as r:
            files = [s["rfilename"] for s in json.load(r).get("siblings", [])]
    except Exception as e:
        log("  lccc listing unavailable:", e); return collections.Counter()
    log("  lccc files:", files)
    pick = [f for f in files if "base" in f and "train" in f] or [f for f in files if "train" in f]
    if not pick: return collections.Counter()
    url = "https://huggingface.co/datasets/thu-coai/lccc/resolve/main/" + pick[0]
    try:
        path = fetch(url, cache)
    except Exception as e:
        log("  lccc download failed:", e); return collections.Counter()
    raw = gzip.open(path, "rt", encoding="utf-8") if path.endswith(".gz") else open(path, encoding="utf-8")
    def lines():
        with raw:
            if path.endswith(".json") or path.endswith(".json.gz"):
                for s in strings(json.load(raw)): yield s.replace(" ", "")
            else:
                for line in raw:
                    try: obj = json.loads(line)
                    except Exception: yield line.replace(" ", ""); continue
                    for s in strings(obj): yield s.replace(" ", "")
    return count_text(lines(), seg, words, LINES)

def main(dict_path, out, cache):
    os.makedirs(cache, exist_ok=True)
    words = load_words(dict_path)
    # Segment with libpinyin's word-level lexicon: the pinyin dictionary is full of phrases
    # (我很想你, 下午三点) that would hide the word pairs inside them.
    lp_big, lp_uni = libpinyin(cache, words)
    seg = Segmenter(log_probs({w: c for w, c in lp_uni.items() if w in words}))
    merged = collections.defaultdict(float)
    for name, fn in [("libpinyin", lambda *_: lp_big), ("subtitles", subtitles), ("lccc", lccc)]:
        try:
            big = fn(cache, words, seg)
        except Exception as e:
            log(name, "failed:", e); continue
        total = sum(big.values())
        log(f"{name}: {len(big)} pairs, {total} tokens")
        if not total: continue
        scale = WEIGHTS[name] * 5e7 / total  # every source counts as a 50M-token corpus
        for k, c in big.items(): merged[k] += c * scale
    by_first = collections.defaultdict(list)
    for (a, b), c in merged.items(): by_first[a].append((b, c))
    n = 0
    with open(out, "w", encoding="utf-8") as f:
        for a in sorted(by_first):
            items = by_first[a]
            total = sum(c for _, c in items)
            keep = sorted((x for x in items if x[1] >= MIN), key=lambda x: -x[1])[:TOP]
            if not keep: continue
            n += len(keep)
            f.write(f"{a}\t{round(total)}\t" + " ".join(f"{b},{round(c)}" for b, c in keep) + "\n")
    log(f"wrote {n} pairs for {len(by_first)} words to {out}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "build/ngram-cache")
