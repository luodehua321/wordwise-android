#!/usr/bin/env python3
"""Compose app-store screenshots and store artwork from emulator captures.

Run after capturing raw 1080 x 2400 screenshots into store/screenshots/raw/.
Only Pillow and a system CJK font are required.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "store/screenshots/raw"
OUT = ROOT / "store/screenshots/marketing"
STORE = ROOT / "store"
ICON = ROOT / "docs/brand/wordwise-icon.png"
HEAVY = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2)
REGULAR = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0)
INK = "#16283C"
INK_TOP = "#0E2233"
INK_BOTTOM = "#22445F"
CREAM = "#FBF7EE"
PAPER = "#F5F7FB"
BLUE = "#3B6FE0"
AMBER = "#E8A34A"
MUTED = "#64758A"
LINE = "#E1E7EF"


def font(size, heavy=False):
    path, index = HEAVY if heavy else REGULAR
    return ImageFont.truetype(path, size, index=index)


def fit_text(draw, text, max_width, start, heavy=True, color=INK, min_size=28):
    size = start
    while size > min_size:
        f = font(size, heavy)
        if draw.textlength(text, font=f) <= max_width:
            return f
        size -= 2
    return font(min_size, heavy)


def background(width=1080, height=1920):
    image = Image.new("RGB", (width, height), PAPER)
    px = image.load()
    for y in range(height):
        for x in range(width):
            # Very soft vertical paper gradient.
            t = y / height
            px[x, y] = (int(246 - 4 * t), int(248 - 4 * t), int(252 - 3 * t))
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((width * .55, -height * .18, width * 1.2, height * .38), fill=(59, 111, 224, 34))
    gd.ellipse((-width * .25, height * .55, width * .42, height * 1.12), fill=(232, 163, 74, 26))
    glow = glow.filter(ImageFilter.GaussianBlur(120))
    image = Image.alpha_composite(image.convert("RGBA"), glow).convert("RGB")
    return image


def round_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def phone_frame(raw_path, width=600):
    raw = Image.open(raw_path).convert("RGB")
    # Keep the real status bar and gesture bar; scale the whole capture.
    height = round(raw.height * width / raw.width)
    screen = raw.resize((width, height), Image.Resampling.LANCZOS)
    radius = 46
    screen = screen.convert("RGBA")
    screen.putalpha(round_mask(screen.size, radius))
    border = 14
    frame = Image.new("RGBA", (width + border * 2, height + border * 2), (0, 0, 0, 0))
    shadow = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((0, 0, frame.width - 1, frame.height - 1),
                                             radius=radius + border, fill=(15, 30, 45, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(32))
    frame.alpha_composite(shadow)
    ImageDraw.Draw(frame).rounded_rectangle((0, 0, frame.width - 1, frame.height - 1),
                                            radius=radius + border, fill="#0B1826")
    frame.alpha_composite(screen, (border, border))
    return frame


def chip(draw, x, y, text, fg=INK, bg="#FFFFFF", size=25):
    f = font(size, True)
    pad_x, pad_y = 22, 12
    w = draw.textlength(text, font=f) + pad_x * 2
    h = size + pad_y * 2
    draw.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=bg, outline=LINE, width=2)
    draw.text((x + pad_x, y + pad_y - 2), text, font=f, fill=fg)
    return w, h


def letterspaced(draw, xy, text, f, fill, spacing=4):
    x, y = xy
    for char in text:
        draw.text((x, y), char, font=f, fill=fill)
        x += draw.textlength(char, font=f) + spacing
    return x


def board(raw, output, index, eyebrow, line1, line2, subtitle, chips):
    image = background()
    draw = ImageDraw.Draw(image)
    # Brand row
    icon = Image.open(ICON).convert("RGBA").resize((60, 60), Image.Resampling.LANCZOS)
    image.paste(icon, (64, 54), icon)
    draw.text((138, 58), "知词", font=font(34, True), fill=INK)
    draw.text((138, 101), "WORDWISE 2.0", font=font(15, True), fill=MUTED)
    draw.text((1016, 62), f"{index:02d} / 06", font=font(18, True), fill=MUTED, anchor="ra")
    # Headline
    f1 = fit_text(draw, line1, 950, 64, heavy=True, min_size=40)
    f2 = fit_text(draw, line2, 950, 64, heavy=True, min_size=40)
    draw.text((64, 172), eyebrow, font=font(20, True), fill=BLUE)
    draw.text((64, 208), line1, font=f1, fill=INK)
    draw.text((64, 208 + f1.size + 14), line2, font=f2, fill=INK)
    draw.text((64, 208 + f1.size + f2.size + 40), subtitle, font=font(26), fill=MUTED)
    # Phone
    phone = phone_frame(RAW / raw, width=600)
    phone_x = (1080 - phone.width) // 2
    phone_y = 440
    image.paste(phone, (phone_x, phone_y), phone)
    # Chips
    total = 0
    widths = []
    for text in chips:
        w = draw.textlength(text, font=font(25, True)) + 44
        widths.append(w)
        total += w
    total += 18 * (len(chips) - 1)
    x = (1080 - total) // 2
    for text, w in zip(chips, widths):
        chip(draw, x, 1834, text)
        x += w + 18
    # Slogan fallback if chips would collide with the phone
    draw.text((64, 1892), "KEEP THE FLOW. GROW THE WORDS.", font=font(17, True), fill=MUTED)
    image.save(OUT / output, quality=95)


def feature_graphic():
    image = background(1024, 500)
    draw = ImageDraw.Draw(image)
    icon = Image.open(ICON).convert("RGBA").resize((150, 150), Image.Resampling.LANCZOS)
    image.paste(icon, (64, 175), icon)
    draw.text((246, 150), "无限流背单词", font=font(58, True), fill=INK)
    draw.text((248, 232), "不设上限 · 只进不退", font=font(27), fill=MUTED)
    draw.text((248, 292), "28,167 词  ·  本地自适应  ·  全离线", font=font(22, True), fill=BLUE)
    draw.text((760, 420), "KEEP THE FLOW.", font=font(15, True), fill=MUTED)
    image.save(STORE / "feature-graphic.png", quality=95)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    icon = Image.open(ICON).convert("RGB").resize((512, 512), Image.Resampling.LANCZOS)
    icon.save(STORE / "icon-512.png", quality=95)
    feature_graphic()

    boards = [
        ("01-learn.png", 1, "INFINITE FLOW", "无限流背单词", "没有每日上限",
         "状态好就多背几个，下一词会自己出现。",
         ["不设每日上限", "动态穿插新词与复习", "28,167 词"]),
        ("02-reveal.png", 2, "READ THE WORD", "先判断，再揭晓", "一次真实的回忆",
         "认识或不认识，都会展开中文、例句与搭配。",
         ["先想再看", "美式 / 英式音标", "不认识的词 5 分钟后回来"]),
        ("03-mistakes.png", 3, "YOUR WORDS", "难词自动进易错本", "按掌握度复习",
         "越容易错的词，越会出现在合适的时间。",
         ["掌握度进度", "可标记已学会", "随时撤销"]),
        ("04-profile.png", 4, "YOUR PACE", "你的水平", "会自己长出来",
         "20 词校准起点，之后根据真实表现缓慢调整。",
         ["10 级水平环", "连续学习天数", "本地保存"]),
        ("05-detail.png", 5, "IN CONTEXT", "一个词", "一整个语境",
         "音标、释义、例句、搭配、词形、派生与易混词。",
         ["逐条来源与许可", "没有内容就不展示", "长内容可滚动"]),
        ("06-privacy.png", 6, "OFFLINE BY DESIGN", "全离线", "真正属于你",
         "无账号、无广告、不申请网络权限。",
         ["无需登录", "无分析 SDK", "记录只在本机"]),
    ]
    for args in boards:
        raw, index, eyebrow, line1, line2, subtitle, chips = args
        output = raw
        if not (RAW / raw).exists():
            print(f"skip missing {raw}")
            continue
        board(raw, output, index, eyebrow, line1, line2, subtitle, chips)
        print("wrote", output)
    # Rebuild the contact sheet from the same six marketing images.
    sheet = Image.new("RGB", (1176, 1392), PAPER)
    for i, entry in enumerate(boards):
        source = OUT / entry[0]
        if source.exists():
            thumb = Image.open(source).convert("RGB").resize((360, 640), Image.Resampling.LANCZOS)
            sheet.paste(thumb, (24 + (i % 3) * 384, 24 + (i // 3) * 680))
    sheet.save(STORE / "screenshots/contact-sheet.png")
    print("store artwork ready")


if __name__ == "__main__":
    main()
