"""Erzeugt das Programmsymbol (ICO) passend zur App: Verlauf Indigo → Violett, weißes Mikrofon."""
import sys
from PIL import Image, ImageDraw

S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
grad = Image.new("RGBA", (S, S))
for y in range(S):
    for x in range(S):
        t = (x + y) / (2 * S)
        r = int(0x63 + (0x8B - 0x63) * t)
        g = int(0x66 + (0x5C - 0x66) * t)
        b = int(0xF1 + (0xF6 - 0xF1) * t)
        grad.putpixel((x, y), (r, g, b, 255))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=112, fill=255)
img.paste(grad, (0, 0), mask)
d = ImageDraw.Draw(img)
w = (255, 255, 255, 255)
d.rounded_rectangle((196, 96, 316, 300), radius=60, fill=w)          # Mikrofonkopf
d.arc((146, 170, 366, 360), start=0, end=180, fill=w, width=26)      # Bügel
d.line((256, 360, 256, 410), fill=w, width=26)
d.rounded_rectangle((186, 402, 326, 428), radius=13, fill=w)
img.save(sys.argv[1], sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
