# -*- coding: utf-8 -*-
"""生成启动图标：mipmap PNG(legacy, API24-25) + adaptive icon(API26+)。
无需第三方库：手写 PNG(zlib+struct)。图形：靛蓝渐变圆角底 + 白色展开书页(梯形) + 深色书脊。"""
import os, struct, zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res')

BG_TOP = (0x43, 0x66, 0xA8)
BG_BOT = (0x27, 0x3B, 0x6B)
PAGE   = (0xF2, 0xF4, 0xF8)
SPINE  = (0x21, 0x32, 0x5C)

def png_chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

def write_png(path, size, pixels):
    raw = b''.join(b'\x00' + b''.join(struct.pack('BBBB', *px) for px in row) for row in pixels)
    png = (b'\x89PNG\r\n\x1a\n'
           + png_chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
           + png_chunk(b'IDAT', zlib.compress(raw, 9))
           + png_chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(png)

def sample(fx, fy):
    """单位坐标采样。书页为梯形：上边宽 0.30、下边宽 0.225，共用书脊中线。"""
    cx, cy = 0.5, 0.5
    # 圆角方块底
    hw = hh = 0.465; rad = 0.115
    dx, dy = abs(fx - cx), abs(fy - cy)
    if dx > hw or dy > hh:
        return (0, 0, 0, 0)
    cxp, cyp = max(dx - (hw - rad), 0.0), max(dy - (hh - rad), 0.0)
    if cxp * cxp + cyp * cyp > rad * rad:
        return (0, 0, 0, 0)
    bg = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * fy) for i in range(3))
    # 书页梯形判定
    top, bot = 0.205, 0.795
    if top <= fy <= bot:
        t = (fy - top) / (bot - top)
        w = 0.30 - 0.075 * t          # 上宽0.30 → 下宽0.225
        off = abs(fx - cx)
        if off < 0.012:               # 书脊缝隙
            return tuple(SPINE + (255,))
        if off <= w:
            return tuple(PAGE + (255,))
    return tuple(bg + (255,))

def render(size, aa=3):
    S = size
    out = []
    for y in range(S):
        row = []
        for x in range(S):
            acc = [0, 0, 0, 0]
            for ay in range(aa):
                for ax in range(aa):
                    r, g, b, a = sample((x + (ax + 0.5) / aa) / S, (y + (ay + 0.5) / aa) / S)
                    acc[0] += r; acc[1] += g; acc[2] += b; acc[3] += a
            n = aa * aa
            row.append((acc[0] // n, acc[1] // n, acc[2] // n, acc[3] // n))
        out.append(row)
    return out

def main():
    densities = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for dpi, size in densities.items():
        d = os.path.join(OUT, 'mipmap-' + dpi)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, 'ic_launcher.png'), size, render(size))
        print('wrote', dpi, size)
    v26 = os.path.join(OUT, 'mipmap-anydpi-v26')
    os.makedirs(v26, exist_ok=True)
    os.makedirs(os.path.join(OUT, 'values'), exist_ok=True)
    os.makedirs(os.path.join(OUT, 'drawable'), exist_ok=True)
    with open(os.path.join(v26, 'ic_launcher.xml'), 'w', encoding='utf-8') as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg"/>
    <foreground android:drawable="@drawable/ic_foreground"/>
</adaptive-icon>
''')
    with open(os.path.join(OUT, 'values', 'colors.xml'), 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_bg">#273B6B</color>\n</resources>\n')
    with open(os.path.join(OUT, 'drawable', 'ic_foreground.xml'), 'w', encoding='utf-8') as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#F2F4F8"
        android:pathData="M27,34 L46,34 L46,74 L23.5,74 Q21,74 21,71.5 L21,38 A6,6 0 0 1 27,34 Z"/>
    <path android:fillColor="#F2F4F8"
        android:pathData="M62,34 L81,34 A6,6 0 0 1 87,38 L87,71.5 Q87,74 84.5,74 L62,74 Z"/>
    <path android:fillColor="#21325C"
        android:pathData="M46,33.5 h4 v41 h-4 z"/>
</vector>
''')

if __name__ == '__main__':
    main()
