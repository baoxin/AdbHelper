#!/usr/bin/env python3
"""ADB 助手 启动图标 + TV Banner 生成脚本（扁平风格，4x 超采样抗锯齿）"""
from PIL import Image, ImageDraw, ImageFont
import os

OUT = os.path.join(os.path.dirname(__file__), "assets_out")
os.makedirs(OUT, exist_ok=True)

# 调色板
BG      = (30, 90, 190, 255)      # 深蓝底
BG_EDGE = (22, 70, 155, 255)     # 底部深一档（扁平双色块）
WHITE   = (255, 255, 255, 255)
TITLEBAR= (227, 236, 248, 255)
DOT_R   = (255, 95, 87, 255)
DOT_Y   = (254, 188, 46, 255)
DOT_G   = (40, 200, 64, 255)
PROMPT  = (34, 197, 94, 255)      # >_ 绿色
ROBOT   = (61, 220, 132, 255)     # 安卓绿
ROBOT_D = (46, 178, 108, 255)

S = 4  # 超采样倍数


def rounded_bg(size, radius):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = int(size * 0.04)
    r = int(size * radius)
    d.rounded_rectangle([m, m, size - m, size - m], radius=r, fill=BG)
    # 底部 1/4 叠加深色块，制造扁平双色层次
    d.rounded_rectangle([m, int(size * 0.72), size - m, size - m], radius=r,
                        fill=BG_EDGE)
    d.rectangle([m, int(size * 0.72) - r, size - m, int(size * 0.72)], fill=BG_EDGE)
    d.rounded_rectangle([m, m, size - m, size - m], radius=r, outline=(255, 255, 255, 28), width=max(2, size // 170))
    return img


def draw_terminal(d, x0, y0, x1, y1, with_prompt=True):
    """白色终端窗口：标题栏 + 三色圆点 + 形状绘制的 >_ 提示符"""
    r = int((x1 - x0) * 0.10)
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=WHITE)
    tb_h = int((y1 - y0) * 0.24)
    d.rounded_rectangle([x0, y0, x1, y0 + tb_h], radius=r, fill=TITLEBAR)
    d.rectangle([x0, y0 + tb_h - r, x1, y0 + tb_h], fill=TITLEBAR)
    # 三色圆点
    dot_r = int(tb_h * 0.22)
    cy = y0 + tb_h // 2
    for i, c in enumerate((DOT_R, DOT_Y, DOT_G)):
        cx = x0 + tb_h * 0.45 + i * dot_r * 3.1
        d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=c)
    if not with_prompt:
        return
    # 形状绘制 ">_"：chevron 两笔 + 下划线矩形，同基线
    w = x1 - x0
    lw = max(4, int(w * 0.055))
    px0 = x0 + w * 0.10
    ym = y0 + tb_h + (y1 - y0 - tb_h) * 0.52
    ch = (y1 - y0 - tb_h) * 0.42          # chevron 高度
    cw = w * 0.16                          # chevron 宽度
    d.line([px0, ym - ch / 2, px0 + cw, ym], fill=PROMPT, width=lw)
    d.line([px0 + cw, ym, px0, ym + ch / 2], fill=PROMPT, width=lw)
    uw = cw * 0.95
    d.rectangle([px0 + cw * 1.45, ym + ch / 2 - lw / 2,
                 px0 + cw * 1.45 + uw, ym + ch / 2 + lw / 2], fill=PROMPT)


def draw_robot(d, cx, cy, r):
    """安卓机器人头：半圆 + 天线 + 双眼"""
    # 天线
    lw = max(3, r // 9)
    d.line([cx - r * 0.55, cy - r * 0.55, cx - r * 0.95, cy - r * 1.05], fill=ROBOT, width=lw)
    d.line([cx + r * 0.55, cy - r * 0.55, cx + r * 0.95, cy - r * 1.05], fill=ROBOT, width=lw)
    # 头部（上半圆）
    d.pieslice([cx - r, cy - r, cx + r, cy + r], 180, 360, fill=ROBOT)
    d.rectangle([cx - r, cy - 2, cx + r, cy + r * 0.18], fill=ROBOT)
    # 双眼
    er = max(3, r // 7)
    for ex in (cx - r * 0.42, cx + r * 0.42):
        d.ellipse([ex - er, cy - r * 0.45 - er, ex + er, cy - r * 0.45 + er], fill=WHITE)


def gen_icon(px):
    size = px * S
    img = rounded_bg(size, 0.22)
    d = ImageDraw.Draw(img)
    # 终端窗口（略偏左上）
    tx0, ty0, tx1, ty1 = int(size*0.14), int(size*0.16), int(size*0.72), int(size*0.60)
    draw_terminal(d, tx0, ty0, tx1, ty1)
    # 机器人（右下，不遮挡提示符）
    draw_robot(d, int(size*0.66), int(size*0.75), int(size*0.17))
    return img.resize((px, px), Image.LANCZOS)


def gen_banner(w, h):
    W, H = w * S, h * S
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=int(H*0.10), fill=BG)
    d.rounded_rectangle([0, int(H*0.72), W - 1, H - 1], radius=int(H*0.10), fill=BG_EDGE)
    d.rectangle([0, int(H*0.72) - int(H*0.10), W - 1, int(H*0.72)], fill=BG_EDGE)
    # 标题
    zh = None
    for idx in (0, 1, 2):
        try:
            zh = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", int(H*0.30), index=idx)
            break
        except Exception:
            continue
    if zh is None:
        zh = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", int(H*0.30))
    d.text((int(W*0.055), int(H*0.06)), "ADB 助手", font=zh, fill=WHITE)
    en = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", int(H*0.11))
    d.text((int(W*0.06), int(H*0.42)), "ADB HELPER", font=en, fill=(191, 215, 242, 255))
    # 终端窗口
    tx0, ty0, tx1, ty1 = int(W*0.06), int(H*0.60), int(W*0.46), int(H*0.93)
    draw_terminal(d, tx0, ty0, tx1, ty1)
    # 机器人
    draw_robot(d, int(W*0.76), int(H*0.78), int(H*0.21))
    return img.resize((w, h), Image.LANCZOS)


# 生成各密度图标
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                 ("xxhdpi", 144), ("xxxhdpi", 192)]:
    gen_icon(px).save(f"{OUT}/ic_launcher_{name}.png")
print("icons done")

# Banner 320x180
gen_banner(320, 180).save(f"{OUT}/banner.png")
print("banner done")

# 预览大图 512 供查看
gen_icon(512).save(f"{OUT}/preview_icon_512.png")
print("preview done")
