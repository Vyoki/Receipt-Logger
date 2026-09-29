"""Draws a synthetic Italian cash & carry invoice (invented content) the way a phone photo looks, for the CI AI check.
Writes invoice.ppm (the model input) and invoice.png (to look at)."""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = sys.argv[1] if len(sys.argv) > 1 else "."
W, H = 1600, 1000
page = Image.new("RGB", (W, H), "white")
d = ImageDraw.Draw(page)
def font(size, bold=False):
    name = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    try:
        return ImageFont.truetype(name, size)
    except OSError:
        return ImageFont.load_default()
f, fb, fs = font(20), font(20, True), font(16)

d.text((60, 30), "ABC S.r.l.", font=font(30, True), fill="black")
d.text((60, 72), "Reg. Imp. PF, C.F. - P.IVA 01234567897", font=fs, fill="black")
d.text((900, 30), "SPETTABILE", font=fs, fill="black")
d.text((900, 55), "RISTORANTE PROVA SAS", font=fb, fill="black")
d.text((900, 82), "P.IVA 09876543217", font=fs, fill="black")
d.text((60, 125), "TIPO DOCUMENTO", font=fs, fill="black"); d.text((420, 125), "N.RO DOCUMENTO", font=fs, fill="black"); d.text((700, 125), "DATA DOCUMENTO", font=fs, fill="black")
d.text((60, 150), "COPIA FATTURA", font=fb, fill="black"); d.text((440, 150), "12A/34567", font=fb, fill="black"); d.text((720, 150), "23/09/2026", font=fb, fill="black")

cols = [60, 170, 245, 930, 1070, 1170, 1300, 1420, 1530]
heads = ["CODICE", "COLLI", "DESCRIZIONE BENI", "TIPO CONF.", "TOT.", "PREZZO", "IMPORTO", "COD"]
rows = [
    ("1000001", "1x1", "BISCOTTI FROLLINI 800 - MARCA A", "SK GR 800", "1", "3,450", "3,45", "10"),
    ("1000002", "1x1", "FETTE BISCOTTATE GR.250 - MARCA B", "SC GR 250", "1", "1,090", "1,09", "04"),
    ("O 1000003", "2x3", "CANDEGGINA NORMALE LT.5 - MARCA C", "FL LT 5", "6", "1,790", "10,74", "22"),
    ("1000004", "1", "FILONE SUINO SV - .", "NC KG", "4,45", "4,390", "19,54", "10"),
    ("O 1000005", "1", "FILETTO B/A KG 3,5+ S/V - .", "CS KG", "4,24", "29,900", "126,78", "10"),
    ("1000006", "1x6", "ACQUA MINERALE NAT.1,5 - MARCA D", "PT CL 150", "6", "0,420", "2,52", "22"),
    ("1000007", "2x1", "UOVA MEDIE 180 - MARCA E", "VA KG 11.3", "2", "40,900", "81,80", "10"),
    ("1000008", "1x4", "PARMIGIANO DOP 15M S/V 800", "KG 0.8", "4", "15,550", "62,20", "04"),
    ("1000009", "1", "SALAMELLA DOLCE", "CF GR", "0,48", "10,210", "4,90", "10"),
    ("1000010", "1x1", "CARBONE VEGETALE KG.10", "NC PZ 1", "1", "11,320", "11,32", "22"),
    ("1000012", "1x10", "CIPOLLA ROSSA KG1X16 CRT - PG", "NC KG 1", "10", "1,490", "14,90", "04"),
    ("1000013", "1x2", "RICOTTA KG.1,5 - MARCA F", "CF GR 1500", "2", "4,850", "9,70", "04"),
]
y = 205
d.line((50, y - 8, W - 40, y - 8), fill="black", width=2)
for i, h in enumerate(heads):
    d.text((cols[i], y), h, font=fs, fill="black")
d.line((50, y + 26, W - 40, y + 26), fill="black", width=1)
right_aligned = {4, 5, 6}
for r, row in enumerate(rows):
    yy = 245 + r * 36
    for i, t in enumerate(row):
        if i in right_aligned:
            w = d.textlength(t, font=f)
            d.text((cols[i + 1] - 25 - w, yy), t, font=f, fill="black")
        else:
            d.text((cols[i], yy), t, font=f, fill="black")
cents = [int(r[6].replace(",", "")) for r in rows]
imponibile = sum(cents)
vat = 0
for rate in set(r[7] for r in rows):
    base = sum(c for c, r in zip(cents, rows) if r[7] == rate)
    vat += round(base * int(rate) / 100)
fmt = lambda c: f"{c // 100},{c % 100:02d}"
yy = 245 + len(rows) * 36 + 20
d.line((50, yy, W - 40, yy), fill="black", width=1)
d.text((1000, yy + 15), "TOTALE IMPONIBILE", font=f, fill="black"); d.text((1330, yy + 15), fmt(imponibile), font=fb, fill="black")
d.text((1000, yy + 45), "TOTALE IVA", font=f, fill="black"); d.text((1330, yy + 45), fmt(vat), font=fb, fill="black")
d.text((1000, yy + 80), "TOTALE DOCUMENTO", font=fb, fill="black"); d.text((1330, yy + 80), fmt(imponibile + vat), font=fb, fill="black")

# Photo: grey table, slight rotation and perspective, soft focus and sensor noise.
random.seed(7)
bg = Image.new("RGB", (W + 120, H + 120), (95, 95, 92))
rot = page.rotate(1.8, resample=Image.BICUBIC, expand=True, fillcolor=(95, 95, 92))
bg.paste(rot, (40, 30))
coeffs = (1, 0.02, 0, 0.004, 1, 0, 0.00002, 0.00001)
photo = bg.transform(bg.size, Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=(95, 95, 92))
photo = photo.filter(ImageFilter.GaussianBlur(0.7))
px = photo.load()
for _ in range(60000):
    x, yv = random.randrange(photo.width), random.randrange(photo.height)
    r, g, b = px[x, yv]
    n = random.randint(-18, 18)
    px[x, yv] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)))
photo = photo.resize((1536, int(photo.height * 1536 / photo.width)), Image.LANCZOS)
photo.save(f"{out}/invoice.ppm")
photo.save(f"{out}/invoice.png")
print("imponibile", fmt(imponibile), "iva", fmt(vat), "totale", fmt(imponibile + vat), "size", photo.size)
