#!/usr/bin/env python3
"""把 ic_launcher.svg 渲染成 Android 需要的各密度图标。

依赖：rsvg-convert（apt install librsvg2-bin）、Pillow。
产出：
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png       （正方形，API<26 用）
  - mipmap-*/ic_launcher_round.png                                 （圆形，API<26 用）
  - ic_launcher_512.png                                            （设计预览）
Adaptive icon（API>=26）走 res/drawable 下的 VectorDrawable，不走这里。
"""
import os
import subprocess

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(ROOT, "..", "src", "main", "res"))
SVG = os.path.join(ROOT, "ic_launcher.svg")
MASTER = os.path.join(ROOT, "ic_launcher_512.png")
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render_master() -> Image.Image:
    subprocess.run(
        ["rsvg-convert", "-w", "512", "-h", "512", SVG, "-o", MASTER],
        check=True,
    )
    return Image.open(MASTER).convert("RGBA")


def circular(img: Image.Image) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main() -> None:
    master = render_master()
    for name, size in DENSITIES.items():
        target = os.path.join(RES, "mipmap-" + name)
        os.makedirs(target, exist_ok=True)
        # 清掉模板自带的 webp，避免和新 PNG 冲突
        for entry in os.listdir(target):
            if entry.endswith(".webp"):
                os.remove(os.path.join(target, entry))
        scaled = master.resize((size, size), Image.LANCZOS)
        scaled.save(os.path.join(target, "ic_launcher.png"))
        circular(scaled).save(os.path.join(target, "ic_launcher_round.png"))
        print(f"{name}: {size}x{size} ok")
    print("master:", MASTER)


if __name__ == "__main__":
    main()
