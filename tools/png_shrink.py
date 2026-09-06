# -*- coding: utf-8 -*-
"""无依赖 PNG 解码(仅色型6 RGBA / 色型2 RGB)+最近邻缩放，用于 adb 截图快速预览。"""
import struct, sys, zlib

def decode(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n'
    pos = 8; idat = b''; w = h = None; ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]
        tag = d[pos+4:pos+8]
        data = d[pos+8:pos+8+ln]
        if tag == b'IHDR':
            w, h, _, ct = struct.unpack('>IIBB', data[:10])
        elif tag == b'IDAT':
            idat += data
        elif tag == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    bpp = 4 if ct == 6 else 3
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(bpp, stride): line[i] = (line[i] + line[i-bpp]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i-bpp] if i >= bpp else 0
                pa, pb, pc = abs(b-c), abs(a-c), abs(a+b-2*c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out += line
        prev = line
    return w, h, ct, bytes(out)

def write_png(path, w, h, rgba_rows):
    raw = b''.join(b'\x00' + row for row in rgba_rows)
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(raw, 6)))
        f.write(chunk(b'IEND', b''))

def shrink(src, dst, maxw=1280):
    w, h, ct, px = decode(src)
    bpp = 4 if ct == 6 else 3
    if w <= maxw:
        rows = [bytes(px[y*w*bpp:(y+1)*w*bpp]) if bpp == 4 else bytes(b'\xff\xff\xff\xff') and b'' for y in range(h)]
    nw = maxw; nh = max(1, round(h * nw / w))
    rows = []
    for y in range(nh):
        sy = min(h-1, int(y * h / nh))
        row = bytearray()
        for x in range(nw):
            sx = min(w-1, int(x * w / nw))
            o = (sy * w + sx) * bpp
            if bpp == 4:
                row += px[o:o+4]
            else:
                row += px[o:o+3] + b'\xff'
        rows.append(bytes(row))
    write_png(dst, nw, nh, rows)

if __name__ == '__main__':
    shrink(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1280)
