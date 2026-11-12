"""Deckle icon: one geometry source -> Android vector drawables + PNG previews/store assets.

usage: python icon.py <repo root> <preview dir>
"""
import os, re, sys
from PIL import Image, ImageDraw, ImageFont

ROOT, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)

TEAL = '#0B6E64'
TEAL_DEEP = '#085A52'
PAPER = '#FFFFFF'
FOLD = '#BFE0DA'
INK = '#0B6E64'
INK_FAINT = '#A9D2CB'
EMBER = '#FF7A3D'

# --- geometry, in the 108 x 108 adaptive-icon viewport (visible safe zone: 21..87) ---
L, R, T = 37.0, 71.0, 30.0          # page left/right/top (corners stay inside the 66dp circle)
FOLD_SIZE = 10.0
RC = 3.0                            # corner radius
# Torn bottom: uneven, soft scallops (hand-drawn feel, not a regular zigzag).
BOTTOM = [(71, 77.6), (64.4, 79.4), (57.6, 77.4), (50.6, 79.6), (43.8, 77.6), (37, 79.0)]

def page_path():
    p = [f'M{L + RC},{T}', f'L{R - FOLD_SIZE},{T}', f'L{R},{T + FOLD_SIZE}', f'L{R},{BOTTOM[0][1]}']
    # Quadratic segments through the midpoints: smooth, irregular wave.
    pts = BOTTOM
    for i in range(1, len(pts)):
        (x0, y0), (x1, y1) = pts[i - 1], pts[i]
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2 + (1.8 if i % 2 else -1.8)
        p.append(f'Q{cx:.1f},{cy:.1f} {x1},{y1}')
    p += [f'L{L},{T + RC}', f'Q{L},{T} {L + RC},{T}', 'Z']
    return ' '.join(p)

FOLD_PATH = f'M{R - FOLD_SIZE},{T} L{R - FOLD_SIZE},{T + FOLD_SIZE - 2} Q{R - FOLD_SIZE},{T + FOLD_SIZE} {R - FOLD_SIZE + 2},{T + FOLD_SIZE} L{R},{T + FOLD_SIZE} Z'
BEAM_Y, BEAM_H = 56.0, 5.0
BEAM = (31.0, 77.0)
LINES_DONE = [(43, 39, 56), (43, 47, 64)]     # (x0, y, x1): crisp, already scanned
LINES_TODO = [(43, 66, 61)]                   # faint, not yet scanned
LINE_W = 3.6

def rrect(x0, y0, x1, y1, r):
    return (f'M{x0 + r},{y0} L{x1 - r},{y0} Q{x1},{y0} {x1},{y0 + r} L{x1},{y1 - r} Q{x1},{y1} {x1 - r},{y1} '
            f'L{x0 + r},{y1} Q{x0},{y1} {x0},{y1 - r} L{x0},{y0 + r} Q{x0},{y0} {x0 + r},{y0} Z')

def line_path(x0, y, x1):
    return f'M{x0},{y} L{x1},{y}'

BEAM_PATH = rrect(BEAM[0], BEAM_Y - BEAM_H / 2, BEAM[1], BEAM_Y + BEAM_H / 2, BEAM_H / 2)

# --- Android vectors ---
def vector(body, comment):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated from tools/icon/icon.py; edit there. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}</vector>
'''

def fill(path, color):
    return f'    <path\n        android:fillColor="{color}"\n        android:pathData="{path}" />\n'

def stroke(path, color):
    return (f'    <path\n        android:strokeColor="{color}"\n        android:strokeWidth="{LINE_W}"\n'
            f'        android:strokeLineCap="round"\n        android:pathData="{path}" />\n')

fg = fill(page_path(), PAPER) + fill(FOLD_PATH, FOLD)
fg += ''.join(stroke(line_path(*l), INK) for l in LINES_DONE)
fg += ''.join(stroke(line_path(*l), INK_FAINT) for l in LINES_TODO)
fg += fill(BEAM_PATH, EMBER)
mono = fill(page_path(), '#FFFFFF') + fill(BEAM_PATH, '#FFFFFF')

res = os.path.join(ROOT, 'app/src/main/res')
open(os.path.join(res, 'drawable/ic_launcher_foreground.xml'), 'w', newline='\n').write(
    vector(fg, 'Deckle: a page with a torn (deckle) bottom edge, mid-scan.'))
open(os.path.join(res, 'drawable/ic_launcher_monochrome.xml'), 'w', newline='\n').write(
    vector(mono, 'Themed-icon silhouette (Android 13+ tints it).'))
open(os.path.join(res, 'drawable/ic_launcher_background.xml'), 'w', newline='\n').write(
    vector(fill('M0,0 L108,0 L108,108 L0,108 Z', TEAL) +
           # A single soft diagonal band for depth: flat colour, no gradient.
           fill('M0,108 L0,70 L108,16 L108,108 Z', TEAL_DEEP), 'Brand teal with one flat diagonal band.'))

# --- tiny rasteriser for the same paths (M L Q Z) ---
def sample(path):
    tokens = re.findall(r'[MLQZ]|-?\d+\.?\d*', path)
    polys, cur, i = [], [], 0
    while i < len(tokens):
        t = tokens[i]
        if t == 'M':
            if cur: polys.append(cur)
            cur = [(float(tokens[i + 1]), float(tokens[i + 2]))]; i += 3
        elif t == 'L':
            cur.append((float(tokens[i + 1]), float(tokens[i + 2]))); i += 3
        elif t == 'Q':
            (x0, y0) = cur[-1]; cx, cy, x1, y1 = map(float, tokens[i + 1:i + 5])
            for k in range(1, 13):
                s = k / 12
                cur.append(((1 - s) ** 2 * x0 + 2 * (1 - s) * s * cx + s * s * x1,
                            (1 - s) ** 2 * y0 + 2 * (1 - s) * s * cy + s * s * y1))
            i += 5
        else:
            i += 1
    if cur: polys.append(cur)
    return polys

def render(size, view=(0, 0, 108, 108), mask=None, mono=False):
    SS = 4
    W = size * SS
    x0, y0, x1, y1 = view
    k = W / (x1 - x0)
    tf = lambda pts: [((x - x0) * k, (y - y0) * k) for x, y in pts]
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if not mono:
        for poly in sample('M0,0 L108,0 L108,108 L0,108 Z'): d.polygon(tf(poly), fill=TEAL)
        for poly in sample('M0,108 L0,70 L108,16 L108,108 Z'): d.polygon(tf(poly), fill=TEAL_DEEP)
    for poly in sample(page_path()): d.polygon(tf(poly), fill=PAPER)
    if not mono:
        for poly in sample(FOLD_PATH): d.polygon(tf(poly), fill=FOLD)
        for (a, y, b), col in [(l, INK) for l in LINES_DONE] + [(l, INK_FAINT) for l in LINES_TODO]:
            r = LINE_W / 2 * k
            (ax, ay), (bx, by) = tf([(a, y), (b, y)])
            d.rounded_rectangle([ax - r, ay - r, bx + r, by + r], radius=r, fill=col)
    for poly in sample(BEAM_PATH): d.polygon(tf(poly), fill=EMBER if not mono else PAPER)
    if mask:
        m = Image.new('L', (W, W), 0)
        md = ImageDraw.Draw(m)
        if mask == 'circle': md.ellipse([0, 0, W - 1, W - 1], fill=255)
        else: md.rounded_rectangle([0, 0, W - 1, W - 1], radius=W * 0.22, fill=255)
        im.putalpha(Image.composite(im.getchannel('A'), m, m))
    return im.resize((size, size), Image.LANCZOS)

VISIBLE = (18, 18, 90, 90)   # what launchers show of the 108 canvas
render(512, VISIBLE).convert('RGB').save(os.path.join(OUT, 'play-icon-512.png'))

# Preview sheet: squircle + circle at launcher sizes, on light and dark wallpapers.
sheet = Image.new('RGB', (980, 300), '#EDEFF2')
dark = Image.new('RGB', (490, 300), '#1B1D21'); sheet.paste(dark, (490, 0))
for bx in (0, 490):
    x = bx + 30
    for s, shape in [(160, 'squircle'), (112, 'circle'), (64, 'squircle'), (44, 'circle')]:
        im = render(s, VISIBLE, mask=shape)
        sheet.paste(im, (x, 150 - s // 2), im)
        x += s + 22
sheet.save(os.path.join(OUT, 'icon-preview.png'))

# Feature graphic 1024 x 500.
fgimg = Image.new('RGB', (1024, 500), TEAL)
d = ImageDraw.Draw(fgimg)
d.polygon([(0, 500), (0, 360), (1024, 60), (1024, 500)], fill=TEAL_DEEP)
glyph = render(420, (24, 18, 84, 90), mono=False)
gl = render(420, (24, 18, 84, 90)).convert('RGBA')
# Draw just the page glyph (no background) for the banner.
def glyph_only(size, view):
    SS = 4; W = size * SS; x0, y0, x1, y1 = view; k = W / (x1 - x0)
    tf = lambda pts: [((x - x0) * k, (y - y0) * k) for x, y in pts]
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0)); dd = ImageDraw.Draw(im)
    for poly in sample(page_path()): dd.polygon(tf(poly), fill=PAPER)
    for poly in sample(FOLD_PATH): dd.polygon(tf(poly), fill=FOLD)
    for (a, y, b), col in [(l, INK) for l in LINES_DONE] + [(l, INK_FAINT) for l in LINES_TODO]:
        r = LINE_W / 2 * k; (ax, ay), (bx, by) = tf([(a, y), (b, y)])
        dd.rounded_rectangle([ax - r, ay - r, bx + r, by + r], radius=r, fill=col)
    for poly in sample(BEAM_PATH): dd.polygon(tf(poly), fill=EMBER)
    return im.resize((size, size), Image.LANCZOS)
g = glyph_only(400, (22, 18, 86, 90))
fgimg.paste(g, (90, 50), g)
font = os.path.join(ROOT, 'app/src/main/res/font/inter.ttf')
def F(sz, wght):
    f = ImageFont.truetype(font, sz)
    try: f.set_variation_by_axes([min(32, max(14, sz)), wght])
    except Exception: pass
    return f
d.text((540, 165), 'Deckle', font=F(104, 700), fill='#FFFFFF')
d.text((544, 295), 'Scan offline. Keep it yours.', font=F(36, 500), fill='#D4ECE8')
fgimg.save(os.path.join(OUT, 'feature-graphic-1024x500.png'))
print('ok')
