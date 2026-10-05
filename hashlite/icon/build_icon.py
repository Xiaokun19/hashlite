#!/usr/bin/env python3
"""哈希计算 · 图标构建脚本（单一源头）

一次生成：
  icon/ic_launcher.svg                      设计源（背景 + 斜体 # + HASH 字形）
  res/drawable/ic_launcher_background.xml   自适应图标背景层（矢量渐变）
  res/drawable/ic_launcher_foreground.xml   自适应图标前景层（# + HASH，标的图）
  res/drawable/ic_launcher_monochrome.xml   主题化单色层
  res/mipmap-*/ic_launcher(.round).png      旧版系统用的位图（rsvg-convert + Pillow）

要点：
  * "HASH" 用 fontTools 从 DejaVuSans-BoldOblique 抽成轮廓路径 —— Android 的
    VectorDrawable 不支持文字，必须转路径；顺便两边的字形完全一致。
  * # 的斜体用 skewX(-12) 实现；VectorDrawable 没有 skew 变换，所以脚本把
    畸变直接烘焙进坐标。
"""
import os
import re
import subprocess

from PIL import Image, ImageDraw
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(ROOT, "..", "src", "main", "res"))
SVG_PATH = os.path.join(ROOT, "ic_launcher.svg")
MASTER_PNG = os.path.join(ROOT, "ic_launcher_512.png")
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf"
WORD = "HASH"

CANVAS = 512.0
CENTER = CANVAS / 2.0          # 256
SAFE_SCALE = 72.0 / CANVAS     # 512 空间 -> 自适应图标 108 层里的可见区（72dp）
LAYER_CENTER = 54.0            # 108 层的中宫

# ---- # 的几何（512 空间，未斜切）----
HASH_CX, HASH_CY = 256.0, 180.0   # 比上一版上移 55
SPAN_V = 77.6                     # 竖笔：中心 ± 77.6
SPAN_H = 77.6                     # 横笔：中心 ± 77.6
BAR_DX = 44.0                     # 两竖 / 两横的间距一半
STROKE_V = 36.8
STROKE_H = 30.4
INNER = 52.8                      # 中心方块边长
SKEW_DEG = 12.0

# ---- HASH 字排 ----
CAP_HEIGHT = 76.0
BASELINE = 404.0
LETTER_SPACING = 13.0


def scale_to_layer(v: float) -> float:
    return (v - CANVAS / 2.0) * SAFE_SCALE + LAYER_CENTER


# ---------- 字形 -> 路径 ----------
# fontTools 的 SVGPathPen 会输出 H/V 简写（单参数），以及 M 后跟多组坐标（隐式 lineto）。
# 不处理这两种情况会把参数顺序整个错位（上一版就是这么画歪的）。
PATH_TOKEN = re.compile(r"([MLHVCSQTZmlhvcsqtz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)")
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "Z": 0}


def transform_path(d: str, sx: float, sy: float, tx: float, ty: float) -> str:
    """把绝对命令路径缩放+平移（SVGPathPen 输出的命令都是绝对的）。

    H/V 会被展开成 L；M 之后的隐式 lineto 也会展开成 L（否则会变成新子路径）。
    """
    tokens = PATH_TOKEN.findall(d)
    out = []
    i = 0
    cx = cy = 0.0

    def emit(x: float, y: float) -> None:
        out.append("%.2f,%.2f" % (tx + x * sx, ty + y * sy))

    while i < len(tokens):
        cmd = tokens[i][0].upper()
        i += 1
        values = []
        while i < len(tokens) and not tokens[i][0]:
            values.append(float(tokens[i][1]))
            i += 1

        arity = ARITY.get(cmd, 2)
        if arity == 0:
            out.append(cmd)
            continue

        first = True
        for k in range(0, len(values) - arity + 1, arity):
            chunk = values[k:k + arity]
            if cmd == "M":
                out.append("M" if first else "L")
                cx, cy = chunk[0], chunk[1]
                emit(cx, cy)
            elif cmd == "L" or cmd == "T":
                out.append("L")
                cx, cy = chunk[0], chunk[1]
                emit(cx, cy)
            elif cmd == "H":
                out.append("L")
                cx = chunk[0]
                emit(cx, cy)
            elif cmd == "V":
                out.append("L")
                cy = chunk[0]
                emit(cx, cy)
            elif cmd == "Q":
                out.append("Q")
                emit(chunk[0], chunk[1])
                cx, cy = chunk[2], chunk[3]
                emit(cx, cy)
            elif cmd == "S":
                out.append("S")
                emit(chunk[0], chunk[1])
                cx, cy = chunk[2], chunk[3]
                emit(cx, cy)
            elif cmd == "C":
                out.append("C")
                emit(chunk[0], chunk[1])
                emit(chunk[2], chunk[3])
                cx, cy = chunk[4], chunk[5]
                emit(cx, cy)
            first = False
    return " ".join(out)


def build_wordmark() -> tuple:
    """返回 (512 空间的 path, 108 空间的 path, 实际宽度)"""
    font = TTFont(FONT_PATH)
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upem = font["head"].unitsPerEm
    cap = getattr(font["OS/2"], "sCapHeight", 0) or int(upem * 0.72)
    scale = CAP_HEIGHT / cap

    pieces = []
    for ch in WORD:
        name = cmap[ord(ch)]
        pen = SVGPathPen(glyphs)
        glyphs[name].draw(pen)
        pieces.append((pen.getCommands(), glyphs[name].width * scale))

    total = sum(width for _, width in pieces) + LETTER_SPACING * (len(pieces) - 1)
    start = CANVAS / 2.0 - total / 2.0

    svg_paths = []
    layer_paths = []
    offset = start
    for d, width in pieces:
        # 字形坐标 y 向上，SVG 向下 -> 翻转
        svg_paths.append(
            transform_path(d, scale, -scale, offset, BASELINE)
        )
        layer_paths.append(
            transform_path(
                d,
                scale * SAFE_SCALE,
                -scale * SAFE_SCALE,
                scale_to_layer(offset),
                scale_to_layer(BASELINE),
            )
        )
        offset += width + LETTER_SPACING
    return " ".join(svg_paths), " ".join(layer_paths), total


# ---------- # 的坐标 ----------
def skew(x: float, y: float) -> tuple:
    k = __import__("math").tan(__import__("math").radians(SKEW_DEG))
    return x - k * (y - HASH_CY), y


def hash_bars() -> list:
    """返回 [(x1,y1,x2,y2,stroke), ...]（已烘焙斜切）"""
    raw = [
        (HASH_CX - BAR_DX, HASH_CY - SPAN_V, HASH_CX - BAR_DX, HASH_CY + SPAN_V, STROKE_V),
        (HASH_CX + BAR_DX, HASH_CY - SPAN_V, HASH_CX + BAR_DX, HASH_CY + SPAN_V, STROKE_V),
        (HASH_CX - SPAN_H, HASH_CY - BAR_DX, HASH_CX + SPAN_H, HASH_CY - BAR_DX, STROKE_H),
        (HASH_CX - SPAN_H, HASH_CY + BAR_DX, HASH_CX + SPAN_H, HASH_CY + BAR_DX, STROKE_H),
    ]
    out = []
    for x1, y1, x2, y2, w in raw:
        a = skew(x1, y1)
        b = skew(x2, y2)
        out.append((a[0], a[1], b[0], b[1], w))
    return out


def hash_square_points() -> list:
    half = INNER / 2.0
    corners = [
        (HASH_CX - half, HASH_CY - half),
        (HASH_CX + half, HASH_CY - half),
        (HASH_CX + half, HASH_CY + half),
        (HASH_CX - half, HASH_CY + half),
    ]
    return [skew(x, y) for x, y in corners]


# ---------- 生成各文件 ----------
def write_svg(wordmark: str) -> None:
    bars = hash_bars()
    sq = hash_square_points()
    bar_svg = "\n".join(
        '    <path d="M%.2f %.2f L%.2f %.2f" stroke-width="%.2f"/>' % (x1, y1, x2, y2, w)
        for x1, y1, x2, y2, w in bars
    )
    sq_svg = "M" + " L".join("%.2f %.2f" % (x, y) for x, y in sq) + " Z"
    svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<!--
  哈希计算 · 应用图标（由 icon/build_icon.py 生成，勿手改；改参数后重跑脚本）
  设计：冷蓝→青渐变圆角方底；斜体 #（上移）；下方 HASH 字排
  字体：DejaVu Sans Bold Oblique（字形已转为路径，不依赖运行环境字体）
-->
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#4C87F8"/>
      <stop offset="0.52" stop-color="#2A6BE8"/>
      <stop offset="1" stop-color="#0E7F76"/>
    </linearGradient>
    <!-- 直线用渐变必须 userSpaceOnUse，否则包围盒退化为 0 导致不渲染 -->
    <linearGradient id="bar" gradientUnits="userSpaceOnUse" x1="170" y1="120" x2="340" y2="300">
      <stop offset="0" stop-color="#FFFFFF"/>
      <stop offset="1" stop-color="#DCEAFF"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.28" cy="0.2" r="0.62">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.26"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
  </defs>

  <rect width="512" height="512" rx="116" fill="url(#bg)"/>
  <rect width="512" height="512" rx="116" fill="url(#glow)"/>

  <!-- #（已烘焙 skewX(-12)） -->
  <g fill="none" stroke="url(#bar)" stroke-linecap="round">
{bar_svg}
  </g>
  <path d="{sq_svg}" fill="#FFFFFF" fill-opacity="0.16"/>

  <!-- HASH -->
  <path d="{wordmark}" fill="#FFFFFF" fill-opacity="0.97"/>
</svg>
'''
    with open(SVG_PATH, "w", encoding="utf-8") as fh:
        fh.write(svg)


def write_android_vectors(wordmark_layer: str) -> None:
    bars = []
    for x1, y1, x2, y2, w in hash_bars():
        bars.append(
            (
                scale_to_layer(x1),
                scale_to_layer(y1),
                scale_to_layer(x2),
                scale_to_layer(y2),
                w * SAFE_SCALE,
            )
        )
    square = "M" + " L".join(
        "%.2f,%.2f" % (scale_to_layer(x), scale_to_layer(y)) for x, y in hash_square_points()
    ) + " Z"

    gradient = '''<aapt:attr name="android:strokeColor">
            <gradient
                android:type="linear"
                android:startX="42"
                android:startY="32"
                android:endX="66"
                android:endY="58">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#FFDCEAFF" />
            </gradient>
        </aapt:attr>'''

    bar_xml = "\n\n".join(
        '''    <path
        android:pathData="M%.2f,%.2f L%.2f,%.2f"
        android:strokeLineCap="round"
        android:strokeWidth="%.2f">
        %s
    </path>''' % (x1, y1, x2, y2, w, gradient)
        for x1, y1, x2, y2, w in bars
    )

    mono_bars = "\n\n".join(
        '''    <path
        android:pathData="M%.2f,%.2f L%.2f,%.2f"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeWidth="%.2f" />''' % (x1, y1, x2, y2, w)
        for x1, y1, x2, y2, w in bars
    )

    header = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 由 icon/build_icon.py 生成，勿手改 -->\n'
    foreground = header + '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

%s

    <path
        android:pathData="%s"
        android:fillColor="#29FFFFFF" />

    <path
        android:pathData="%s"
        android:fillColor="#F7FFFFFF" />
</vector>
''' % (bar_xml, square, wordmark_layer)

    monochrome = header + '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

%s

    <path
        android:pathData="%s"
        android:fillColor="#FFFFFFFF" />
</vector>
''' % (mono_bars, wordmark_layer)

    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8") as fh:
        fh.write(foreground)
    with open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as fh:
        fh.write(monochrome)


def write_pngs() -> None:
    subprocess.run(
        ["rsvg-convert", "-w", "512", "-h", "512", SVG_PATH, "-o", MASTER_PNG],
        check=True,
    )
    master = Image.open(MASTER_PNG).convert("RGBA")
    for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        target = os.path.join(RES, "mipmap-" + name)
        os.makedirs(target, exist_ok=True)
        for entry in os.listdir(target):
            if entry.endswith(".webp"):
                os.remove(os.path.join(target, entry))
        scaled = master.resize((size, size), Image.LANCZOS)
        scaled.save(os.path.join(target, "ic_launcher.png"))
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        rounded = scaled.copy()
        rounded.putalpha(mask)
        rounded.save(os.path.join(target, "ic_launcher_round.png"))


def main() -> None:
    wordmark, wordmark_layer, width = build_wordmark()
    write_svg(wordmark)
    write_android_vectors(wordmark_layer)
    write_pngs()
    print("wordmark 宽度(512 空间): %.0f px  ->  108 层中 width %.1f dp" % (width, width * SAFE_SCALE))
    print("ok:", SVG_PATH)


if __name__ == "__main__":
    main()
