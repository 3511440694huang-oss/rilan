#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""日览 · 图标资源生成脚本（纯色化 + 缩放 + 圆形裁切 + 自适应前景）

输入一张 1024x1024 的生成图（默认见 DEFAULT_SRC），一次产出：

  项目根目录
    icon-master.png              1024x1024 方形完整图标（已纯色化）
    icon-flat.png                1024x1024 纯色扁平版（与 master 同图）
    icon-foreground-432.png      432x432 自适应前景（透明背景，主体居中）
    icon-foreground-preview.png  432x432 预览：前景铺在靛蓝底上（RGB，方便肉眼看）
    icon-preview.png             1160x400 并排预览（方形 / 圆形 / 自适应）

  app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}
    ic_launcher.png              48 / 72 / 96 / 144 / 192
    ic_launcher_round.png        同尺寸，圆形裁切，四角透明
    ic_launcher_foreground.png   108 / 162 / 216 / 324 / 432（透明背景）

  app/src/main/res/values/colors.xml
    rilan_icon 保证等于图标背景色 #0D1224（值相同则不写文件）

用法:
    cd /sdcard/WORK/01-开发项目/日览
    python3 tools/make_icons.py                # 用默认生成图
    python3 tools/make_icons.py 其他源图.png    # 用指定源图（会居中裁成正方形）

设计要点:
  * 调色板固定 3 色，所有像素就近归并 → 真正扁平、0 渐变、0 噪点。
  * 前景层从 master 的背景色抠出（边界 flood fill，失败时按行跨度兜底）：
    卡片内部被奶白包住的深靛蓝新闻条不会被误抠，
    前景叠在 @color/rilan_icon 上合成后与 master 完全一致。
  * 自适应图标可见视口 = 中央 72dp / 108dp（系统会放大 ~1.5x）。
    主体取画布 46% ≈ 可见区 69%，与方形图里卡片占 68% 的观感一致，
    卡片对角半径 ≈ 35dp < 36dp 圆形遮罩半径，圆角不会被裁掉。
"""

from __future__ import annotations

import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 选定的生成图（候选 A2：简报卡片 + 日出 + 新闻线）
DEFAULT_SRC = "/sdcard/WORK/generated-images/seedream-20261117-000147-df35b2.png"

# 固定调色板（不放雾蓝，避免奶白/靛蓝抗锯齿过渡像素被误归到蓝色形成描边）
PALETTE = [
    ("#0D1224", (0x0D, 0x12, 0x24), "indigo"),
    ("#F6EFE3", (0xF6, 0xEF, 0xE3), "cream"),
    ("#F3B28B", (0xF3, 0xB2, 0x8B), "peach"),
]
BG = PALETTE[0][1]
BG_HEX = PALETTE[0][0]
BG_RGB = BG + (255,)
PREVIEW_BG = (0x07, 0x09, 0x12)  # 与 App rilan_bg 一致，预览用

MASTER = 1024
SAFE = 0.46                       # 自适应前景主体 bbox 占 108dp 画布比例
ADAPTIVE_VIEWPORT = 72.0 / 108.0  # 可见视口占画布比例

SQUARE_DP = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
             ("xxhdpi", 144), ("xxxhdpi", 192)]
FG_DP = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
         ("xxhdpi", 324), ("xxxhdpi", 432)]


# ---------------------------------------------------------------- 纯色化
def quantize(img: Image.Image):
    """把图片所有像素归并到 PALETTE 的 3 个颜色，再做 5x5 众数滤波去噪。"""
    img = img.convert("RGB")
    arr = np.asarray(img, dtype=np.int32)
    cols = np.array([p[1] for p in PALETTE], dtype=np.int32)
    dist = ((arr[:, :, None, :] - cols[None, None, :, :]) ** 2).sum(axis=-1)
    labels = dist.argmin(axis=-1).astype(np.uint8)

    # 抹掉生成图的纸纹/渐变噪点和 1-2px 毛边
    labels = np.asarray(
        Image.fromarray(labels, "L").filter(ImageFilter.ModeFilter(5))
    )

    counts = np.bincount(labels.ravel(), minlength=len(PALETTE))
    stats = {PALETTE[i][2]: int(counts[i]) for i in range(len(PALETTE))}
    rgb = cols[labels].astype(np.uint8)
    return Image.fromarray(rgb, "RGB"), labels, stats


def center_square(img: Image.Image) -> Image.Image:
    w, h = img.size
    s = min(w, h)
    left, top = (w - s) // 2, (h - s) // 2
    return img.crop((left, top, left + s, top + s))


# ---------------------------------------------------------------- 前景抠图
def background_mask(labels: np.ndarray) -> np.ndarray:
    """返回保留掩码：背景色(0) 中从画布四边可达的部分被去掉。"""
    h, w = labels.shape
    try:
        lab_img = Image.fromarray(labels.copy(), "L")
        sentinel = 255
        for pt in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
            if lab_img.getpixel(pt) == 0:
                ImageDraw.floodfill(lab_img, pt, sentinel)
        return np.asarray(lab_img) != sentinel
    except Exception:  # 兜底：圆角卡片是凸形，逐行取奶白/桃子的 min..max 跨度
        keep = np.zeros_like(labels, dtype=bool)
        solid = labels != 0
        for y in np.nonzero(solid.any(axis=1))[0]:
            xs = np.nonzero(solid[y])[0]
            if xs.size:
                keep[y, xs.min():xs.max() + 1] = True
        return keep


def extract_subject(master_img: Image.Image, labels: np.ndarray) -> Image.Image:
    """抠出透明背景的主体（奶白卡片 + 桃子太阳 + 靛蓝新闻线）。"""
    keep = background_mask(labels)
    rgb = np.asarray(master_img, dtype=np.uint8)
    alpha = np.where(keep, 255, 0).astype(np.uint8)
    subject = Image.fromarray(np.dstack([rgb, alpha]), "RGBA")

    ys, xs = np.nonzero(alpha)
    if ys.size == 0:
        raise RuntimeError("前景抠图失败：没有找到主体")
    bbox = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    return subject.crop(bbox)


def render_foreground(subject: Image.Image, size: int) -> Image.Image:
    """把主体按 SAFE 比例居中放到透明画布上（画布 = 108dp 版式）。"""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(round(size * SAFE))
    w, h = subject.size
    scale = target / float(max(w, h))
    nw = max(1, int(round(w * scale)))
    nh = max(1, int(round(h * scale)))
    scaled = subject.resize((nw, nh), Image.LANCZOS)
    canvas.paste(scaled, ((size - nw) // 2, (size - nh) // 2), scaled.split()[3])
    return canvas


def over(rgba: Image.Image, bg_rgb=BG) -> Image.Image:
    """把 RGBA 叠到纯色底上，返回 RGB。"""
    base = Image.new("RGBA", rgba.size, bg_rgb + (255,))
    return Image.alpha_composite(base, rgba).convert("RGB")


# ---------------------------------------------------------------- 形态渲染
def circle_mask(size: int, ss: int = 4) -> Image.Image:
    big = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def render_square(master_img: Image.Image, size: int) -> Image.Image:
    return master_img.resize((size, size), Image.LANCZOS)


def render_round(master_img: Image.Image, size: int) -> Image.Image:
    out = master_img.resize((size, size), Image.LANCZOS).convert("RGBA")
    out.putalpha(circle_mask(size))
    return out


# ---------------------------------------------------------------- 根目录产物
def build_root_assets(master_img: Image.Image, subject: Image.Image):
    paths = {}

    paths["icon-master.png"] = os.path.join(ROOT, "icon-master.png")
    master_img.save(paths["icon-master.png"], optimize=True)

    paths["icon-flat.png"] = os.path.join(ROOT, "icon-flat.png")
    master_img.save(paths["icon-flat.png"], optimize=True)

    fg432 = render_foreground(subject, 432)
    paths["icon-foreground-432.png"] = os.path.join(ROOT, "icon-foreground-432.png")
    fg432.save(paths["icon-foreground-432.png"], optimize=True)

    # 前景铺在靛蓝底上的预览（108dp 全画布，方便核对主体位置）
    fg_preview = over(fg432)
    paths["icon-foreground-preview.png"] = os.path.join(
        ROOT, "icon-foreground-preview.png")
    fg_preview.save(paths["icon-foreground-preview.png"], optimize=True)

    # 并排预览：方形 / 圆形 / 自适应（自适应按 72dp 可见视口模拟真实桌面观感）
    W, H, tile = 1160, 400, 360
    canvas = Image.new("RGB", (W, H), PREVIEW_BG)

    canvas.paste(render_square(master_img, tile), (20, 20))

    rnd = render_round(master_img, tile)
    canvas.paste(rnd.convert("RGB"), (400, 20), rnd.split()[3])

    full = over(fg432)
    side = int(round(432 * ADAPTIVE_VIEWPORT))
    off = (432 - side) // 2
    visible = full.crop((off, off, off + side, off + side)).resize(
        (tile, tile), Image.LANCZOS)
    canvas.paste(visible, (780, 20), circle_mask(tile))

    paths["icon-preview.png"] = os.path.join(ROOT, "icon-preview.png")
    canvas.save(paths["icon-preview.png"], optimize=True)
    return paths


# ---------------------------------------------------------------- mipmap
def build_mipmaps(master_img: Image.Image, subject: Image.Image):
    paths = {}
    for dens, px in SQUARE_DP:
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)

        p = os.path.join(d, "ic_launcher.png")
        render_square(master_img, px).save(p, optimize=True)
        paths[p] = px

        p = os.path.join(d, "ic_launcher_round.png")
        render_round(master_img, px).save(p, optimize=True)
        paths[p] = px

    for dens, px in FG_DP:
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher_foreground.png")
        render_foreground(subject, px).save(p, optimize=True)
        paths[p] = px
    return paths


# ---------------------------------------------------------------- colors.xml
def sync_colors_xml(log):
    p = os.path.join(RES, "values", "colors.xml")
    if not os.path.exists(p):
        log("!! 没找到 colors.xml: " + p)
        return
    with open(p, "r", encoding="utf-8") as f:
        txt = f.read()
    new = re.sub(r'(<color name="rilan_icon">)[^<]*(</color>)',
                 r"\g<1>" + BG_HEX + r"\g<2>", txt)
    if new != txt:
        with open(p, "w", encoding="utf-8") as f:
            f.write(new)
        log("colors.xml: rilan_icon -> " + BG_HEX + "（已更新）")
    else:
        log("colors.xml: rilan_icon 已是 " + BG_HEX + "（无需改动）")


# ---------------------------------------------------------------- main
def main() -> int:
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    log = lambda m: print(m, flush=True)  # noqa: E731

    log("源图: " + src)
    if not os.path.exists(src):
        log("!! 源图不存在，请传入正确路径")
        return 1

    raw = center_square(Image.open(src)).resize((MASTER, MASTER), Image.LANCZOS)
    master_img, labels, stats = quantize(raw)
    total = float(MASTER * MASTER)
    log("纯色化后颜色占比: " + "  ".join(
        "%s(%s)=%.1f%%" % (p[2], p[0], 100.0 * stats[p[2]] / total) for p in PALETTE))

    subject = extract_subject(master_img, labels)
    bw, bh = subject.size
    log("前景主体 bbox: %dx%d（占画布 %.0f%%）" % (bw, bh, 100.0 * max(bw, bh) / MASTER))

    log("生成根目录产物 …")
    root_paths = build_root_assets(master_img, subject)
    for name in ("icon-master.png", "icon-flat.png", "icon-foreground-432.png",
                 "icon-foreground-preview.png", "icon-preview.png"):
        log("  ✓ " + name)

    log("生成 mipmap …")
    mip_paths = build_mipmaps(master_img, subject)
    sync_colors_xml(log)

    # ---- 尺寸核对表
    log("\n路径 / 实际尺寸 / 模式 / 大小")
    rows = list(mip_paths.items()) + [(p, None) for p in root_paths.values()]
    ok = True
    for p, want in rows:
        if not os.path.exists(p):
            log("  !! 缺失 " + p)
            ok = False
            continue
        with Image.open(p) as im:
            w, h = im.size
            mode = im.mode
        good = (want is None or (w == h == want))
        if not good:
            ok = False
        log("  %-5s %-58s %4dx%-4d %-5s %7d B" % (
            "OK" if good else "BAD", os.path.relpath(p, ROOT), w, h, mode,
            os.path.getsize(p)))

    log("")
    log("全部尺寸核对通过 ✅" if ok else "有尺寸核对失败 ❌")
    if ok:
        log("预览图: " + root_paths["icon-preview.png"])
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
