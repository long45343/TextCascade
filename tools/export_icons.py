#!/usr/bin/env python3
"""从 docs/branding/logo.svg 重新导出 Android 图标资源。

用法（需要 cairosvg + Pillow）:
    python tools/export_icons.py

产物:
    app/src/main/res/drawable/ic_icon.xml       自适应图标前景（矢量，直接写入）
    app/src/main/res/mipmap-*/ic_small_icon.png 通知栏小图标（白色剪影，5 档密度）

设计约束（改尺寸前请先读）:
  - 自适应图标画板 108dp，启动器只显示中心 72dp，图形还需落在直径 66dp 的安全圆内。
  - 设计稿里图形占白色卡片的 69%，这里保持同一比例，避免图标在桌面上显得过大或过小。
  - 通知栏小图标必须是「白色 + 透明」的单色剪影，系统会自行着色。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SVG = os.path.join(ROOT, "docs", "branding", "logo.svg")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 源设计稿（512x512）实测几何
GLYPH_CX, GLYPH_CY = 256.0, 235.625
GLYPH_H = 325.75
CARD = 472.0          # 白色卡片边长（含圆角，不含投影）
VISIBLE_DP = 72.0     # 启动器可见区
SMALL_ICON_GLYPH_DP = 20.0   # 通知栏 24dp 视口里留 2dp 边

DENSITIES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}


def load_paths():
    svg = open(SVG, encoding="utf-8").read()
    groups = re.findall(r'<g transform="([^"]+)" fill="#111111"[^>]*>\s*<path d="([^"]+)"', svg)
    if len(groups) != 2:
        sys.exit(f"预期 logo.svg 含 2 个图形组，实际 {len(groups)} 个")
    return groups


def collapsed(groups):
    """把 SVG 的 translate/scale/translate 折成一个 (sx, sy, tx, ty) 供 VectorDrawable 使用。"""
    out = []
    for transform, _ in groups:
        m = re.match(r"translate\(([\d.]+),\s*([\d.]+)\)\s*scale\(([\d.]+)(?:,\s*([\d.]+))?\)"
                     r"\s*translate\(([-\d.]+),\s*([-\d.]+)\)", transform)
        if not m:
            sys.exit(f"无法解析 transform: {transform}")
        tx1, ty1, sx, sy, tx2, ty2 = m.groups()
        sx = float(sx)
        sy = float(sy) if sy else sx
        out.append((sx, sy,
                    float(tx1) + sx * float(tx2),
                    float(ty1) + sy * float(ty2)))
    return out


def write_adaptive_icon(groups):
    k = (GLYPH_H / CARD) * VISIBLE_DP / GLYPH_H     # 可见区比例 -> 画板 dp 缩放
    glyph_dp = (GLYPH_H / CARD) * VISIBLE_DP
    parts = [f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Adaptive icon foreground: clipboard + sync-node mark from the 512x512 brand logo.
     Source artwork: glyph bbox 237 x {GLYPH_H} at ({GLYPH_CX:g}, {GLYPH_CY:g}), i.e. 69% of the logo card.
     Launchers show the central {VISIBLE_DP:g}dp of this 108dp layer, so a {glyph_dp:.1f}dp glyph here
     renders at the same 69% proportion and stays inside the 66dp safe circle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="54"
        android:translateY="54"
        android:scaleX="{k:.6f}"
        android:scaleY="{k:.6f}">
        <group
            android:translateX="{-GLYPH_CX:g}"
            android:translateY="{-GLYPH_CY:g}">''']
    for (sx, sy, tx, ty), (_, path) in zip(collapsed(groups), groups):
        parts.append(f'''            <group
                android:translateX="{tx:.4f}"
                android:translateY="{ty:.4f}"
                android:scaleX="{sx:g}"
                android:scaleY="{sy:g}">
                <path
                    android:fillColor="#FF111111"
                    android:pathData="{path}" />
            </group>''')
    parts.append("""        </group>
    </group>
</vector>
""")
    out = os.path.join(RES, "drawable", "ic_icon.xml")
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(parts))
    print("wrote", out)


def write_small_icons(groups):
    import cairosvg
    k = SMALL_ICON_GLYPH_DP / GLYPH_H
    body = "".join(
        f'<g transform="translate(12,12) scale({k:.6f}) translate({-GLYPH_CX:g},{-GLYPH_CY:g})">'
        f'<g transform="{t}" fill="#FFFFFF"><path d="{p}"/></g></g>'
        for t, p in groups)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
           f'viewBox="0 0 24 24">{body}</svg>')
    tmp = os.path.join(HERE, ".ic_small_icon.svg")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(svg)
    try:
        for dpi, px in DENSITIES.items():
            out = os.path.join(RES, f"mipmap-{dpi}", "ic_small_icon.png")
            cairosvg.svg2png(url=tmp, write_to=out, output_width=px, output_height=px)
            print("wrote", out, f"{px}px")
    finally:
        os.remove(tmp)


def main():
    groups = load_paths()
    write_adaptive_icon(groups)
    write_small_icons(groups)


if __name__ == "__main__":
    main()
