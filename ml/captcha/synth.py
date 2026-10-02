# Генератор синтетических капч в стиле funtime: 5 "набросанных" цифр,
# случайные цвета/повороты/масштаб, волнистые искажения, шумовые линии и окружности.
import math
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 512, 384

FONT_DIRS = [r'C:\Windows\Fonts']


def _load_font(rng, size):
    names = ['arialbd.ttf', 'arial.ttf', 'arialblack.ttf', 'calibrib.ttf', 'consolab.ttf',
             'courbd.ttf', 'georgiab.ttf', 'impact.ttf', 'segoeuib.ttf', 'tahoma.ttf',
             'timesbd.ttf', 'trebucbd.ttf', 'verdanab.ttf', 'framd.ttf', 'pala.ttf']
    rng.shuffle(names)
    for n in names:
        for d in FONT_DIRS:
            try:
                return ImageFont.truetype(str(__import__('pathlib').Path(d) / n), size)
            except OSError:
                continue
    return ImageFont.load_default()


def _wave_warp(img, rng):
    arr = np.asarray(img).astype(np.float32)
    h, w = arr.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    ax, ay = rng.uniform(4, 14), rng.uniform(4, 14)
    fx, fy = rng.uniform(0.008, 0.02), rng.uniform(0.008, 0.02)
    px = rng.uniform(0, 6.28)
    py = rng.uniform(0, 6.28)
    dx = (ax * np.sin(xs * fx + px) + ay * np.sin(ys * fy * 0.7 + py)).astype(np.int32)
    dy = (ay * np.sin(ys * fy + py) + ax * np.sin(xs * fx * 0.6 + px)).astype(np.int32)
    xs2 = np.clip(xs + dx, 0, w - 1).astype(np.int32)
    ys2 = np.clip(ys + dy, 0, h - 1).astype(np.int32)
    out = arr[ys2, xs2]
    return Image.fromarray(out.astype(np.uint8))


def _noise(img, rng):
    d = ImageDraw.Draw(img)
    for _ in range(rng.randint(8, 14)):  # длинные дуги через весь холст
        col = tuple(rng.randint(0, 255) for _ in range(3))
        wdt = rng.randint(2, 6)
        pts = [(rng.uniform(-50, W + 50), rng.uniform(-50, H + 50)) for _ in range(3)]
        d.line([c for p in pts for c in p], fill=col, width=wdt, joint='curve')
    for _ in range(rng.randint(8, 16)):  # окружности
        col = tuple(rng.randint(0, 200) for _ in range(3))
        r = rng.uniform(8, 60)
        cx, cy = rng.uniform(0, W), rng.uniform(0, H)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col,
                  width=rng.randint(1, 4))
    return img


def _gradient_bg(rng):
    base = rng.randint(225, 255)
    img = Image.new('RGB', (W, H), (base, base, base))
    d = ImageDraw.Draw(img)
    c1 = rng.randint(-30, 10)
    for y in range(H):
        v = max(0, min(255, base + int(c1 * math.sin(y / 40 + rng.uniform(0, 1)))))
        d.line([0, y, W, y], fill=(v, v, v))
    return img


def _hatch_fill(mask, rng, col):
    # Штрихованная заливка глифа: диагональные линии внутри маски цифры
    w, h = mask.size
    hatch = Image.new('L', (w, h), 0)
    hd = ImageDraw.Draw(hatch)
    step = rng.randint(4, 8)
    for off in range(-h, w + h, step):
        hd.line([off, 0, off + h, h], fill=255, width=rng.randint(1, 3))
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    light = tuple(min(255, c + rng.randint(20, 80)) for c in col)
    solid = Image.new('RGBA', (w, h), light + (255,))
    out.paste(solid, (0, 0), Image.composite(hatch, Image.new('L', (w, h), 0), mask))
    return out


def gen_captcha(rng, code=None):
    code = code or ''.join(str(rng.randint(0, 9)) for _ in range(5))
    img = _gradient_bg(rng)
    # бежево-кремовый оттенок как на реальных капчах
    tint = rng.uniform(0.0, 0.5)
    arr = np.asarray(img).astype(np.float32)
    arr[:, :, 0] = np.clip(arr[:, :, 0] * (1 + 0.05 * tint), 0, 255)
    arr[:, :, 1] = np.clip(arr[:, :, 1] * (1 + 0.02 * tint), 0, 255)
    arr[:, :, 2] = np.clip(arr[:, :, 2] * (1 - 0.08 * tint), 0, 255)
    img = Image.fromarray(arr.astype(np.uint8))
    layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    xs = [70 + i * (W - 140) / 4 for i in range(5)]
    for ch, cx in zip(code, xs):
        size = int(rng.uniform(110, 170))
        font = _load_font(rng, size)
        ang = rng.uniform(-35, 35)
        col = tuple(rng.randint(30, 230) for _ in range(3))
        pad = 30
        bbox = Image.new('RGBA', (size + pad * 2, size + pad * 2), (0, 0, 0, 0))
        bd = ImageDraw.Draw(bbox)
        ox, oy = pad, pad + rng.randint(-8, 8)
        style = rng.random()
        if style < 0.35:
            # контурный глиф: несколько проходов только обводкой со смещением
            for _ in range(rng.randint(2, 3)):
                dxy = (rng.randint(-6, 6), rng.randint(-6, 6))
                bd.text((ox + dxy[0], oy + dxy[1]), ch, font=font, fill=col + (90,),
                        stroke_width=rng.randint(2, 4), stroke_fill=col)
        elif style < 0.7:
            # набросанность: несколько проходов со смещением
            for _ in range(rng.randint(2, 3)):
                dxy = (rng.randint(-5, 5), rng.randint(-5, 5))
                bd.text((ox + dxy[0], oy + dxy[1]), ch, font=font, fill=col,
                        stroke_width=rng.randint(0, 3), stroke_fill=col)
        else:
            # сплошной + штриховка поверх
            bd.text((ox, oy), ch, font=font, fill=col,
                    stroke_width=rng.randint(0, 2), stroke_fill=col)
            alpha = bbox.split()[3]
            hatch = _hatch_fill(alpha, rng, col)
            layer_off = Image.new('RGBA', bbox.size, (0, 0, 0, 0))
            dxy = (rng.randint(-4, 4), rng.randint(-4, 4))
            layer_off.alpha_composite(hatch, dxy)
            layer_off.alpha_composite(bbox)
            bbox = layer_off
        bbox = bbox.rotate(ang, resample=Image.BILINEAR, expand=True)
        # клампинг: цифра целиком остаётся на холсте, иначе метка нечитаема
        hw, hh = bbox.width / 2, bbox.height / 2
        px = min(max(cx, hw + 2), W - hw - 2)
        py = min(max(H / 2 + rng.uniform(-45, 45), hh + 2), H - hh - 2)
        layer.alpha_composite(bbox, (int(px - hw), int(py - hh)))

    img = Image.alpha_composite(img.convert('RGBA'), layer).convert('RGB')
    img = _wave_warp(img, rng)
    img = _noise(img, rng)
    return img, code


if __name__ == '__main__':
    import sys
    rng = random.Random(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    for i in range(int(sys.argv[1])):
        im, c = gen_captcha(rng)
        im.save(f'synth/{c}_{i}.png')
