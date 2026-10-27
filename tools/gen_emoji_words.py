#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji_words.txt: Chinese and English words -> emoji, for suggestions.

From Unicode CLDR annotations (common/annotations/zh.xml and en.xml, Unicode License), limited to
the emoji in assets/emoji.txt. One line per word, best emoji first:  敬礼<TAB>🫡
Usage: python3 tools/gen_emoji_words.py zh.xml en.xml
"""
import collections, re, sys

ANN = re.compile(r'<annotation cp="([^"]+)"( type="tts")?>([^<]+)</annotation>')
# words too common to mean "an emoji is wanted" every time they are typed
STOP = set("是 好 的 我 你 他 她 它 人 脸 一 不 有 在 了 和 也 都 就 大 小 上 下 中 手 心 中国".split()) | {
    "face", "person", "man", "woman", "the", "and", "yes", "good", "hand", "sign", "symbol", "ok", "no"}
MAX_PER_WORD = 3
# everyday chat words CLDR misses or ranks oddly (加油 is a fuel pump there); these come first
MANUAL = {
    "加油": "💪✊🔥", "爱心": "❤️💕😍", "爱你": "❤️😘🥰", "点赞": "👍", "赞": "👍", "厉害": "👍🐮😎", "牛": "🐮👍",
    "哈哈": "😂🤣😄", "哈哈哈": "😂🤣", "笑死": "🤣😂", "哭": "😭😢", "哭了": "😭", "难过": "😢😞", "生气": "😠😡",
    "无语": "😑🙄", "尴尬": "😅😬", "害羞": "😊☺️", "惊讶": "😮😲", "震惊": "😱😲", "好的": "👌🫡", "收到": "🫡👌",
    "ok": "👌", "谢谢": "🙏😊", "感谢": "🙏", "拜托": "🙏", "抱抱": "🤗", "晚安": "🌙😴💤", "早安": "☀️🌞",
    "庆祝": "🎉🥳", "恭喜": "🎉👏", "鼓掌": "👏", "握手": "🤝", "再见": "👋", "拜拜": "👋", "吃饭": "🍚😋",
    "睡觉": "😴💤", "困": "😪😴", "酷": "😎", "思考": "🤔", "嘘": "🤫", "亲亲": "😘", "心碎": "💔", "玫瑰": "🌹",
    "生日快乐": "🎂🎉", "新年快乐": "🧧🎉", "红包": "🧧", "666": "👍🐮",
}
GENERIC = 12  # words naming more emoji than this (脸, 动物) are categories, not wishes

def bare(e):
    return e.replace("️", "")

def main(zh, en):
    known = {}
    for line in open("app/src/main/assets/emoji.txt", encoding="utf-8"):
        if not line.startswith("#"):
            for e in line.split(): known[bare(e)] = e
    order = {}
    for line in open("app/src/main/assets/emoji.txt", encoding="utf-8"):
        if not line.startswith("#"):
            for e in line.split(): order.setdefault(e, len(order))
    words = collections.defaultdict(list)  # word -> [(rank, popularity, emoji)]
    for path, chinese in ((zh, True), (en, False)):
        text = open(path, encoding="utf-8").read()
        for cp, tts, body in ANN.findall(text):
            e = known.get(bare(cp))
            if not e: continue
            names = [body.strip()] if tts else [w.strip() for w in body.split("|")]
            for w in names:
                w = w.replace("’", "'")
                if not chinese: w = w.lower()
                if (len(w) < 2 and not tts if chinese else len(w) < 3) or w in STOP or " " in w and not tts: continue
                # the emoji's own name ranks above emoji that merely mention the word
                words[w].append((0 if tts else 1, order.get(e, 99999), e))
    for w in MANUAL: words.setdefault(w, [])
    n = 0
    with open("app/src/main/assets/emoji_words.txt", "w", encoding="utf-8") as f:
        for w in sorted(words):
            items = sorted(set(words[w]))
            if w not in MANUAL and sum(1 for r, _, _ in items if r == 1) > GENERIC and not any(r == 0 for r, _, _ in items): continue
            out = []
            for e in re.findall(r"\S(?:\ufe0f|\u200d\S)*", MANUAL.get(w, "")) if w in MANUAL else []:
                if e not in out: out.append(e)
            for _, _, e in items:
                if e not in out: out.append(e)
            f.write(f"{w}\t{''.join(out[:MAX_PER_WORD])}\n")
            n += 1
    print(f"wrote {n} words")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
