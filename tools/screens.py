#!/usr/bin/env python3
"""Takes screenshots of the keyboard on a running emulator (used by the screenshots workflow).

Finds the keyboard by its background color, then taps / holds keys at positions computed
from the same dp proportions the app uses.
"""
import os, subprocess, sys, time
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "shots"
os.makedirs(OUT, exist_ok=True)
GRAY = (228, 230, 235)  # light skin background

def adb(*a, out=None):
    if out:
        with open(out, "wb") as f:
            subprocess.run(["adb", "exec-out", *a], stdout=f, check=True)
    else:
        subprocess.run(["adb", "shell", *a], check=True)

def shot(name):
    path = os.path.join(OUT, name + ".png")
    adb("screencap", "-p", out=path)
    return path

def density():
    o = subprocess.run(["adb", "shell", "wm", "density"], capture_output=True, text=True).stdout
    return int(o.strip().split()[-1]) / 160.0

def keyboard_top(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    x = w // 2 + 3
    near = lambda c: all(abs(c[i] - GRAY[i]) < 6 for i in range(3))
    y = h - 1
    while y > 0 and not near(im.getpixel((x, y))):  # skip navigation bar
        y -= 1
    bottom = y
    while y > 0 and (near(im.getpixel((x, y))) or near(im.getpixel((5, y)))):
        y -= 1
    return y + 1, bottom, w

def tap(x, y):
    adb("input", "tap", str(int(x)), str(int(y)))
    time.sleep(1.2)

def hold_and_shot(x, y, x2, y2, ms, at, name):
    p = subprocess.Popen(["adb", "shell", "input", "swipe", str(int(x)), str(int(y)), str(int(x2)), str(int(y2)), str(ms)])
    time.sleep(at)
    shot(name)
    p.wait()
    time.sleep(1.5)

d = density()
# CI emulators often show "Pixel Launcher isn't responding": hide error dialogs
subprocess.run(["adb", "shell", "settings", "put", "global", "hide_error_dialogs", "1"])
subprocess.run(["adb", "shell", "am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS"])
time.sleep(2)
adb("am", "start", "-n", "com.shuyu.voiceime/.SettingsActivity", "--ez", "demo", "true")
time.sleep(5)
first = shot("01_pinyin")
top, bottom, W = keyboard_top(first)
dp = lambda v: v * d
print("keyboard top", top, "bottom", bottom, "density", d)
bar_y = top + dp(22)
rows_top = top + dp(44) + dp(3)
row_y = lambda i: rows_top + dp(60) * (i + 0.5)
mid = lambda i: dp(57) + (W - dp(114)) / 8 * (2 * i + 1)   # toolbar tool slots 0..3
back = (dp(26), bar_y)

# space bar in the qwerty layout
weights = [1.15, 1.15, 1.05, 3.4, 1.05, 1.15, 1.6]
space_x = (sum(weights[:3]) + weights[3] / 2) / sum(weights) * W
space_y = row_y(3)

# typing: n i h a o
for letter in "nihao":
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    for ri, r in enumerate(rows):
        if letter in r:
            i = r.index(letter)
            if ri == 0: x = (i + 0.5) / 10 * W
            elif ri == 1: x = (i + 1.0) / 10 * W
            else:
                unit = W / (1.45 * 2 + 7)
                x = unit * (1.45 + i + 0.5)
            adb("input", "tap", str(int(x)), str(int(row_y(ri))))
            time.sleep(0.4)
time.sleep(1)
shot("02_composing")
tap(space_x, space_y)

# voice overlay: hold space, then slide up to the middle card
hold_and_shot(space_x, space_y, space_x, space_y, 3500, 2.0, "03_voice_hold")
hold_and_shot(space_x, space_y, W / 2, rows_top + dp(40), 4000, 3.2, "04_voice_slide_up")

tap(mid(3), bar_y); shot("05_emoji"); tap(*back)
tap(mid(1), bar_y); shot("06_edit"); tap(*back)
tap(mid(0), bar_y); shot("07_modes")
# pick 拼音九键 (first cell of the mode grid)
tap(W / 8, top + dp(44) + dp(20) + (dp(60) * 4 + dp(6) - dp(40)) / 4)
shot("08_pinyin9")
tap(mid(0), bar_y)
tap(W / 8 * 3, top + dp(44) + dp(20) + (dp(60) * 4 + dp(6) - dp(40)) / 4)  # back to 拼音全键
tap(weights[0] / 2 / sum(weights) * W, row_y(3)); shot("09_numbers")
tap(W * 0.92, row_y(3))
tap((sum(weights[:5]) + weights[5] / 2) / sum(weights) * W, row_y(3)); shot("10_symbols")

# dark skin (follows the system)
adb("input", "keyevent", "KEYCODE_BACK")
adb("cmd", "uimode", "night", "yes")
time.sleep(3)
adb("am", "start", "-n", "com.shuyu.voiceime/.SettingsActivity", "--ez", "demo", "true")
time.sleep(5)
shot("11_dark")
hold_and_shot(space_x, space_y, space_x, space_y, 3500, 2.0, "12_dark_voice")
