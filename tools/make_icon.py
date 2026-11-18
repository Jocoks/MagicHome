#!/usr/bin/env python3
"""生成 MagicHome 的自适应图标。

风格：柔和渐变底（雾蓝 → 藕紫 → 浅粉）+ 四边内侧阴影 + 圆角化的白色国际象棋马
      + 马身一圈细黑线 + 极淡竖向渐变 + 轻微羽化。

用法（仓库根目录）：python3 tools/make_icon.py
依赖：pip install --break-system-packages pillow cairosvg

产物：
  res/mipmap-*/ic_launcher_background.png
  res/mipmap-*/ic_launcher_foreground.png
"""
import io
import os
import sys

import cairosvg
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SVG = os.path.join(HERE, "chess_knight.svg")
SS = 4  # 超采样

GLYPH_H = 0.385      # 马高占画布比例
ROUND = 0.0035       # 圆角化半径（占画布比例，越大越「软」）
FEATHER = 0.0025     # 马身边缘羽化
LINE_WIDTH = 0.011   # 黑线宽度
LINE_ALPHA = 0.85
LINE_BLUR = 0.005
LINE_COLOR = (32, 32, 42)

GRADIENT = [(210, 230, 250), (226, 223, 247), (251, 231, 235)]
RIM_INSET = 0.168
RIM_ALPHA = 0.30
RIM_BLUR = 0.075
SHADOW_COLOR = (146, 148, 196)

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(px):
    img = Image.new("RGB", (px, px))
    p = img.load()
    n = len(GRADIENT) - 1
    for y in range(px):
        for x in range(px):
            t = (x * 0.35 + y * 0.65) / px
            i = min(int(t * n), n - 1)
            p[x, y] = lerp(GRADIENT[i], GRADIENT[i + 1], t * n - i)
    return img


def rounded_mask(px, inset, radius):
    m = Image.new("L", (px, px), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [inset, inset, px - 1 - inset, px - 1 - inset], radius=radius, fill=255)
    return m


def render_background(px):
    img = gradient(px).convert("RGBA")
    tile = rounded_mask(px, 0, int(px * 0.235))
    inner = rounded_mask(px, int(px * RIM_INSET), int(px * 0.12))
    ring = ImageChops.subtract(tile, inner)
    sh = Image.new("RGBA", (px, px), SHADOW_COLOR + (255,))
    sh.putalpha(ring.point(lambda v: int(v * RIM_ALPHA)))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(max(1, int(px * RIM_BLUR)))))
    return img


def _erode(im, n):
    for _ in range(n):
        im = im.filter(ImageFilter.MinFilter(9))
    return im


def _dilate(im, n):
    for _ in range(n):
        im = im.filter(ImageFilter.MaxFilter(9))
    return im


def knight_mask(px):
    """把马画成蒙版，并做开/闭运算把尖角磨圆。"""
    svg = open(SVG, encoding="utf-8").read().replace("<path ", '<path fill="#FFFFFFFF" ')
    glyph = Image.open(io.BytesIO(cairosvg.svg2png(
        bytestring=svg.encode(), output_width=px * 2, output_height=px * 2))).convert("RGBA")
    glyph = glyph.crop(glyph.getbbox())
    h = int(px * GLYPH_H)
    glyph = glyph.resize((max(1, int(glyph.width * h / glyph.height)), h), Image.LANCZOS)
    ox = (px - glyph.width) // 2
    oy = (px - glyph.height) // 2
    mask = Image.new("L", (px, px), 0)
    mask.paste(glyph.getchannel("A").point(lambda v: 255 if v > 128 else 0), (ox, oy))
    r = max(1, int(px * ROUND / 4))
    mask = _dilate(_erode(mask, r), r)      # 磨掉凸角
    mask = _erode(_dilate(mask, r), r)      # 磨掉凹角
    return mask


def render_foreground(px):
    mask = knight_mask(px)
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))

    # 马身边缘细黑线
    ring = ImageChops.subtract(_dilate(mask, max(1, int(px * LINE_WIDTH / 4))), mask)
    line = Image.new("RGBA", (px, px), LINE_COLOR + (255,))
    line.putalpha(ring.point(lambda v: int(v * LINE_ALPHA)))
    canvas.alpha_composite(line.filter(
        ImageFilter.GaussianBlur(max(1, int(px * LINE_BLUR)))))

    # 白色马身 + 极淡竖向渐变 + 轻微羽化
    body = Image.new("RGBA", (px, px), (255, 255, 255, 255))
    tint = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    tp = tint.load()
    for y in range(px):
        a = int(26 * y / px)
        for x in range(px):
            tp[x, y] = (236, 238, 250, a)
    body.alpha_composite(tint)
    body.putalpha(mask.filter(ImageFilter.GaussianBlur(max(1, int(px * FEATHER)))))
    canvas.alpha_composite(body)
    return canvas


def main():
    for dpi, size in DENSITIES.items():
        d = os.path.join(ROOT, "res", "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        render_background(size * SS).resize((size, size), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher_background.png"), optimize=True)
        render_foreground(size * SS).resize((size, size), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        print("%-8s %3dpx" % (dpi, size))


if __name__ == "__main__":
    sys.exit(main())
