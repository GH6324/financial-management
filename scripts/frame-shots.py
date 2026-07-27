#!/usr/bin/env python3
"""
frame-shots.py · 给发版截图套外框(规范:所有对外截图必须有外框,裸截图不行)

  python3 scripts/frame-shots.py /tmp/release-shots/v1.6.0

- mobile_*.jpg → iPhone 黑框(圆角机身 + 底部 home indicator),统一输出 584×1228
- pc_*.jpg     → 浏览器窗口框(三点 + 地址栏胶囊),宽度按内容自适应(局部裁剪图比例各异)
- 原图保留为 *.raw.jpg,套框结果覆盖原名 → 上传流程不用改

依赖 Pillow。见 memory feedback_screenshot_frames。
"""
import sys, pathlib
from PIL import Image, ImageDraw

MOBILE_W, MOBILE_H = 584, 1228          # 与 docs/screenshots/fs_m_*.jpg 对齐
BEZEL = 16                               # 机身黑边
BODY_R = 46                              # 机身圆角
SCREEN_R = 30                            # 屏幕圆角

PC_BAR = 34                              # 浏览器顶栏高
PC_BORDER = 1


def rounded_mask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def frame_mobile(src: pathlib.Path, dst: pathlib.Path):
    img = Image.open(src).convert('RGB')
    inner_w, inner_h = MOBILE_W - 2 * BEZEL, MOBILE_H - 2 * BEZEL
    # 等比缩放到内屏宽,超高则从顶部裁(首屏优先)
    scale = inner_w / img.width
    img = img.resize((inner_w, max(1, round(img.height * scale))), Image.LANCZOS)
    if img.height > inner_h:
        img = img.crop((0, 0, inner_w, inner_h))
    elif img.height < inner_h:
        pad = Image.new('RGB', (inner_w, inner_h), (255, 252, 245))
        pad.paste(img, (0, 0))
        img = pad
    img.putalpha(rounded_mask(img.size, SCREEN_R))

    body = Image.new('RGB', (MOBILE_W, MOBILE_H), (255, 255, 255))
    shell = Image.new('RGB', (MOBILE_W, MOBILE_H), (29, 29, 31))
    shell.putalpha(rounded_mask(shell.size, BODY_R))
    body.paste(shell, (0, 0), shell)
    body.paste(img, (BEZEL, BEZEL), img)
    d = ImageDraw.Draw(body)
    # home indicator
    hx, hy = MOBILE_W // 2, MOBILE_H - BEZEL // 2 - 3
    d.rounded_rectangle([hx - 52, hy - 2, hx + 52, hy + 2], radius=2, fill=(105, 105, 110))
    body.save(dst, quality=88, optimize=True)
    return body.size


def frame_pc(src: pathlib.Path, dst: pathlib.Path):
    img = Image.open(src).convert('RGB')
    w, h = img.size
    W, H = w + 2 * PC_BORDER, h + PC_BAR + PC_BORDER
    out = Image.new('RGB', (W, H), (232, 228, 220))
    d = ImageDraw.Draw(out)
    d.rectangle([0, 0, W - 1, PC_BAR - 1], fill=(238, 234, 226))
    d.line([0, PC_BAR - 1, W - 1, PC_BAR - 1], fill=(198, 190, 176))
    for i, c in enumerate([(237, 106, 94), (245, 191, 79), (97, 197, 84)]):
        cx = 18 + i * 20
        d.ellipse([cx - 6, PC_BAR // 2 - 6, cx + 6, PC_BAR // 2 + 6], fill=c)
    # 地址栏胶囊
    bx0, bx1 = 92, min(W - 24, 92 + int(W * 0.42))
    d.rounded_rectangle([bx0, 7, bx1, PC_BAR - 8], radius=9, fill=(252, 250, 245), outline=(206, 198, 184))
    d.text((bx0 + 12, PC_BAR // 2 - 6), 'dixi-token.top', fill=(122, 112, 96))
    d.rectangle([0, 0, W - 1, H - 1], outline=(198, 190, 176))
    out.paste(img, (PC_BORDER, PC_BAR))
    out.save(dst, quality=86, optimize=True)
    return out.size


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    d = pathlib.Path(sys.argv[1])
    for f in sorted(d.glob('*.jpg')):
        if f.name.endswith('.raw.jpg'):
            continue
        raw = f.with_suffix('.raw.jpg')
        if not raw.exists():
            f.replace(raw)
        size = frame_mobile(raw, f) if f.name.startswith('mobile_') else frame_pc(raw, f)
        print(f'framed {f.name}  {size[0]}x{size[1]}  {round(f.stat().st_size/1024)}KB')


if __name__ == '__main__':
    main()
