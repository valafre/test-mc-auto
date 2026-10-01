#!/usr/bin/env python3
"""Génère les textures du GUI (icônes blanches teintées à l'affichage, logo, icône Voidgloom, bandeau d'accueil).

Les icônes sont dessinées en 32x32 avec anti-crénelage (suréchantillonnage x8) : nettes à l'échelle GUI 2 et 4.
Usage : python3 tools/generate_gui_assets.py   (nécessite Pillow)
"""
import math
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "automod", "textures", "gui")
SS = 8          # suréchantillonnage
SIZE = 32       # taille finale des icônes
W = (255, 255, 255, 255)
STROKE = 2.6    # épaisseur du trait (en pixels finaux)


def canvas(size=SIZE):
    return Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))


def finish(img, size=SIZE):
    return img.resize((size, size), Image.LANCZOS)


def s(v):
    return v * SS


def line(d, pts, w=STROKE, color=W):
    pts = [(s(x), s(y)) for x, y in pts]
    d.line(pts, fill=color, width=int(w * SS), joint="curve")
    r = w * SS / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse((x - r, y - r, x + r, y + r), fill=color)


def circle(d, cx, cy, r, w=STROKE, fill=False, color=W):
    box = (s(cx - r), s(cy - r), s(cx + r), s(cy + r))
    if fill:
        d.ellipse(box, fill=color)
    else:
        d.ellipse(box, outline=color, width=int(w * SS))


def rrect(d, x0, y0, x1, y1, r, w=STROKE, fill=False, color=W):
    box = (s(x0), s(y0), s(x1), s(y1))
    if fill:
        d.rounded_rectangle(box, radius=s(r), fill=color)
    else:
        d.rounded_rectangle(box, radius=s(r), outline=color, width=int(w * SS))


def save(img, name):
    img.save(os.path.join(OUT, name + ".png"))


def icon_home():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(4, 15), (16, 4.5), (28, 15)])
    line(d, [(7.5, 13), (7.5, 27), (24.5, 27), (24.5, 13)])
    line(d, [(13, 27), (13, 19.5), (19, 19.5), (19, 27)])
    return finish(i)


def icon_grid():
    i = canvas(); d = ImageDraw.Draw(i)
    for x0, y0 in ((5, 5), (18, 5), (5, 18), (18, 18)):
        rrect(d, x0, y0, x0 + 9, y0 + 9, 2.5, fill=True)
    return finish(i)


def icon_gear():
    i = canvas(); d = ImageDraw.Draw(i)
    cx = cy = 16
    circle(d, cx, cy, 9.2, fill=True)
    for k in range(8):
        a = k * math.pi / 4
        tx, ty = cx + math.cos(a) * 11.2, cy + math.sin(a) * 11.2
        pts = []
        for da, rr in ((-0.23, 8.5), (-0.17, 13.2), (0.17, 13.2), (0.23, 8.5)):
            pts.append((s(cx + math.cos(a + da) * rr), s(cy + math.sin(a + da) * rr)))
        d.polygon(pts, fill=W)
    hole = Image.new("L", i.size, 0)
    ImageDraw.Draw(hole).ellipse((s(cx - 4.6), s(cy - 4.6), s(cx + 4.6), s(cy + 4.6)), fill=255)
    i.paste((0, 0, 0, 0), mask=hole)
    return finish(i)


def icon_user():
    i = canvas(); d = ImageDraw.Draw(i)
    circle(d, 16, 11, 5.4)
    d.arc((s(6.5), s(18.5), s(25.5), s(37)), 180, 360, fill=W, width=int(STROKE * SS))
    return finish(i)


def icon_info():
    i = canvas(); d = ImageDraw.Draw(i)
    circle(d, 16, 16, 12)
    line(d, [(16, 14.5), (16, 22.5)])
    circle(d, 16, 10.4, 1.5, fill=True)
    return finish(i)


def icon_cube():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(16, 4), (27, 10), (27, 22), (16, 28), (5, 22), (5, 10), (16, 4)])
    line(d, [(5, 10), (16, 16), (27, 10)])
    line(d, [(16, 16), (16, 28)])
    return finish(i)


def icon_folder():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(4.5, 9), (4.5, 25), (27.5, 25), (27.5, 11), (15, 11), (12.5, 7.5), (4.5, 7.5), (4.5, 9)])
    return finish(i)


def icon_search():
    i = canvas(); d = ImageDraw.Draw(i)
    circle(d, 14, 14, 8.5)
    line(d, [(20.5, 20.5), (27, 27)])
    return finish(i)


def icon_arrow_left():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(26, 16), (6, 16)])
    line(d, [(14, 8), (6, 16), (14, 24)])
    return finish(i)


def icon_chevron_down():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(8, 12), (16, 20), (24, 12)], w=3)
    return finish(i)


def icon_sliders():
    i = canvas(); d = ImageDraw.Draw(i)
    for y, kx in ((8, 20), (16, 11), (24, 21)):
        line(d, [(5, y), (27, y)], w=2.2)
        circle(d, kx, y, 3.2, fill=True)
    return finish(i)


def icon_sword():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(6, 26), (23, 9)], w=3)
    line(d, [(20, 4), (28, 4), (28, 12)], w=2.4)
    line(d, [(8, 18), (14, 24)], w=2.6)
    return finish(i)


def icon_move():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(16, 4), (16, 28)]); line(d, [(4, 16), (28, 16)])
    line(d, [(12, 8), (16, 4), (20, 8)], w=2.2); line(d, [(12, 24), (16, 28), (20, 24)], w=2.2)
    line(d, [(8, 12), (4, 16), (8, 20)], w=2.2); line(d, [(24, 12), (28, 16), (24, 20)], w=2.2)
    return finish(i)


def icon_target():
    i = canvas(); d = ImageDraw.Draw(i)
    circle(d, 16, 16, 11); circle(d, 16, 16, 4.2, fill=True)
    for a, b in (((16, 2), (16, 7)), ((16, 25), (16, 30)), ((2, 16), (7, 16)), ((25, 16), (30, 16))):
        line(d, [a, b], w=2.2)
    return finish(i)


def icon_puzzle():
    i = canvas(); d = ImageDraw.Draw(i)
    rrect(d, 5, 9, 23, 27, 3)
    circle(d, 14, 6.5, 3.2, fill=True)
    circle(d, 26, 18, 3.2, fill=True)
    return finish(i)


def icon_shield():
    i = canvas(); d = ImageDraw.Draw(i)
    line(d, [(16, 4), (26, 8), (26, 16), (16, 28), (6, 16), (6, 8), (16, 4)])
    line(d, [(11.5, 15.5), (15, 19), (21, 12)], w=2.4)
    return finish(i)


def logo():
    size = 48
    i = canvas(size); d = ImageDraw.Draw(i)
    purple = (139, 92, 246, 255)
    d.rounded_rectangle((s(3), s(3), s(45), s(45)), radius=s(11), fill=(139, 92, 246, 40), outline=purple, width=int(2.2 * SS))
    # étoile à quatre branches
    cx = cy = 24
    pts = []
    for k in range(8):
        a = k * math.pi / 4 - math.pi / 2
        r = 12.5 if k % 2 == 0 else 3.6
        pts.append((s(cx + math.cos(a) * r), s(cy + math.sin(a) * r)))
    d.polygon(pts, fill=purple)
    d.ellipse((s(cx + 7), s(cy - 12), s(cx + 10.5), s(cy - 8.5)), fill=(196, 181, 253, 255))
    return finish(i, size)


def voidgloom_icon():
    base = Image.new("RGBA", (16, 16), (17, 17, 24, 255))
    px = base.load()
    for x in range(16):
        for y in range(16):
            if y in (0, 15) or x in (0, 15):
                px[x, y] = (30, 30, 42, 255)
    for x, y in ((3, 7), (4, 7), (3, 8), (4, 8), (11, 7), (12, 7), (11, 8), (12, 8)):
        px[x, y] = (196, 107, 255, 255)
    for x in range(5, 11):
        px[x, 11] = (40, 24, 60, 255)
    return base.resize((32, 32), Image.NEAREST)


def banner():
    w, h = 640, 192
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        top, bot = (38, 22, 74), (13, 13, 24)
        row = tuple(int(top[k] + (bot[k] - top[k]) * t) for k in range(3))
        for x in range(w):
            px[x, y] = (*row, 255)
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for r in range(180, 0, -6):
        a = int(70 * (1 - r / 180) ** 2)
        gd.ellipse((w * 0.62 - r * 1.6, h * 0.55 - r, w * 0.62 + r * 1.6, h * 0.55 + r), fill=(139, 92, 246, a))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)
    # silhouettes de tours
    for x0, tw, th in ((380, 22, 110), (430, 16, 80), (470, 28, 140), (520, 18, 95), (560, 24, 120), (600, 14, 70), (330, 14, 62), (290, 20, 88)):
        d.polygon([(x0, h), (x0 + tw * 0.5, h - th), (x0 + tw, h)], fill=(10, 8, 20, 255))
    for k in range(40):
        sx, sy = (k * 97) % w, (k * 53) % (h // 2)
        d.point((sx, sy), fill=(210, 190, 255, 160))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    icons = {
        "home": icon_home, "modules": icon_grid, "settings": icon_gear, "profile": icon_user, "info": icon_info,
        "cube": icon_cube, "folder": icon_folder, "search": icon_search, "arrow_left": icon_arrow_left,
        "chevron_down": icon_chevron_down, "tab_general": icon_sliders, "tab_combat": icon_sword,
        "tab_move": icon_move, "tab_target": icon_target, "tab_mechanics": icon_puzzle, "tab_failsafe": icon_shield,
    }
    for name, fn in icons.items():
        save(fn(), "icon_" + name)
    save(logo(), "logo")
    save(voidgloom_icon(), "module_voidgloom")
    save(banner(), "banner")
    print("assets écrits dans", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
