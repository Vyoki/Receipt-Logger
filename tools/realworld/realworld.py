#!/usr/bin/env python3
"""
Real-world reading check: public invoice and receipt datasets (with the values printed on them), made imperfect the way
phone photos are (crooked, curled, blurred, dark, low resolution), read with OCR and written in the same format as the
app's "Recognised text" report, so the app's own parser reads them (see core RealWorldBenchTest).

The datasets are not part of this repository (licences); clone them into one folder:
  git clone --depth 1 https://github.com/invoice-x/invoice2data          (MIT, real invoices EN/FR/NL/DE/PL)
  git clone --depth 1 https://github.com/zzzDavid/ICDAR-2019-SROIE        (SROIE scanned receipts, research use)
  git clone --depth 1 https://github.com/Eoxia/eoxia-dataset-receipts     (French receipt photos, GPL-3)
  git clone --depth 1 https://github.com/mouadhamri/invoice_dataset       (900 invoices in 9 layouts, French)
  OCR languages: git clone --filter=blob:none --sparse https://github.com/tesseract-ocr/tessdata_fast
                 (then git sparse-checkout set --no-cone ita.traineddata fra.traineddata ...)

Usage: realworld.py <datasets dir> <output dir> [--limit N] [--variants clean,crooked,...]
Output: <output dir>/<case>__<variant>.txt (report format) and <case>.truth (key=value lines).

The OCR here is Tesseract, a stand-in for the phone's ML Kit: it finds the same words but groups them into lines its
own way, so results show weaknesses of the reading rules, not exact phone numbers.
"""
import zlib, glob, json, math, os, random, re, subprocess, sys, tempfile, xml.etree.ElementTree as ET
from concurrent.futures import ProcessPoolExecutor

import cv2
import numpy as np

MAX_SIDE = 2200

# ---------------------------------------------------------------- truth


def cents(v):
    if v is None or v == "":
        return None
    s = str(v).strip().replace("RM", "").replace("€", "").replace(" ", "")
    if re.fullmatch(r"-?\d{1,3}(\.\d{3})*,\d{1,2}", s):
        s = s.replace(".", "").replace(",", ".")
    s = s.replace(",", "")
    try:
        return int(round(float(s) * 100))
    except ValueError:
        return None


def iso_date(s):
    if not s:
        return None
    s = s.strip()
    m = re.match(r"^(\d{4})-(\d{2})-(\d{2})", s)
    if m:
        return m.group(0)
    m = re.match(r"^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})$", s)
    if m:
        d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if y < 100:
            y += 2000
        if mo > 12:  # US order
            d, mo = mo, d
        return f"{y:04d}-{mo:02d}-{d:02d}"
    m = re.match(r"^(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\s+(\d{4})$", s)
    if m:
        months = "jan feb mar apr may jun jul aug sep oct nov dec".split()
        mo = months.index(m.group(2).lower()) + 1 if m.group(2).lower() in months else None
        if mo:
            return f"{int(m.group(3)):04d}-{mo:02d}-{int(m.group(1)):02d}"
    return None


def case(id_, images, truth, lang):
    return {"id": id_, "images": images, "truth": {k: v for k, v in truth.items() if v not in (None, "", [])}, "lang": lang}


def invoice2data(root):
    out = []
    for js in sorted(glob.glob(f"{root}/invoice2data/tests/compare/*.json")):
        base = js[:-5]
        pdf = base + ".pdf"
        if not os.path.exists(pdf):
            continue
        t = json.load(open(js))[0]
        lines = [{"d": l.get("name") or l.get("description"), "q": l.get("qty"), "t": cents(l.get("price_subtotal"))} for l in t.get("lines", []) if isinstance(l, dict)]
        out.append(case("i2d-" + os.path.basename(base), [pdf], {
            "seller": t.get("issuer"), "date": iso_date(t.get("date")), "number": t.get("invoice_number"),
            "total": cents(t.get("amount")), "subtotal": cents(t.get("amount_untaxed")), "vat": cents(t.get("amount_tax")),
            "currency": t.get("currency"), "lines": lines,
        }, "eng+fra+nld+deu+pol"))
    return out


def sroie(root, limit):
    out = []
    keys = sorted(glob.glob(f"{root}/ICDAR-2019-SROIE/data/key/*.json"))
    random.Random(7).shuffle(keys)
    for k in keys[:limit]:
        img = k.replace("/key/", "/img/").replace(".json", ".jpg")
        if not os.path.exists(img):
            continue
        t = json.load(open(k))
        out.append(case("sroie-" + os.path.basename(k)[:-5], [img], {
            "seller": t.get("company"), "date": iso_date(t.get("date")), "total": cents(t.get("total")),
        }, "eng"))
    return out


def eoxia(root):
    out = []
    for js in sorted(glob.glob(f"{root}/eoxia-dataset-receipts/entities/*.json")):
        raw = open(js).read()
        raw = raw[raw.index("{"): raw.rindex("}") + 1]
        try:
            t = json.loads(raw)
        except ValueError:
            continue
        img = f"{root}/eoxia-dataset-receipts/images/" + os.path.basename(js).replace(".json", ".jpg")
        if not os.path.exists(img):
            continue
        fin = t.get("financials") or t.get("amounts") or {}
        tr = t.get("transaction") or {}
        merchant = t.get("merchant") or {}
        items = t.get("items") or []
        out.append(case("eoxia-" + os.path.basename(js)[:-5], [img], {
            "seller": merchant.get("name"), "date": iso_date(tr.get("date") or t.get("date")),
            "total": cents(fin.get("total_ttc")), "subtotal": cents(fin.get("total_ht")), "vat": cents(fin.get("total_vat")),
            "number": tr.get("receipt_number"),
            "lines": [{"d": i.get("description"), "q": i.get("quantity"), "t": cents(i.get("total_price"))} for i in items],
        }, "fra"))
    return out


def invoice_dataset(root, per_model):
    out = []
    for model in sorted(glob.glob(f"{root}/invoice_dataset/invoice_dataset_model_*")):
        for x in sorted(glob.glob(f"{model}/xml/*.xml"))[:per_model]:
            img = x.replace("/xml/", "/images/").replace(".xml", ".jpg")
            if not os.path.exists(img):
                continue
            r = ET.parse(x).getroot()
            g = lambda n: (r.findtext(n) or "").strip() or None
            sub, tax = cents(g("total_untaxed")), cents(g("tax_amount"))
            lines = [{"d": l.findtext("description"), "q": l.findtext("quantity"), "t": cents(l.findtext("sub_total"))} for l in r.iter("line")]
            out.append(case(f"invds-{os.path.basename(model)[-1]}-{os.path.basename(x)[:-4]}", [img], {
                "seller": g("supplier"), "date": iso_date(g("invoice_date")), "number": g("invoice_number"),
                "subtotal": sub, "vat": tax, "total": (sub + tax) if sub is not None and tax is not None else None,
                "lines": lines,
            }, "fra"))
    return out


# ---------------------------------------------------------------- imperfect photos


def upright(img, tessdata):
    """A landscape photo of a document stored sideways (EXIF lost): turned the way a person holds the phone, choosing
    the quarter turn in which the OCR finds more real words."""
    h, w = img.shape[:2]
    if w <= h:
        return img

    def words(x):
        small = cv2.resize(x, None, fx=1800 / max(x.shape[:2]), fy=1800 / max(x.shape[:2]))
        with tempfile.TemporaryDirectory() as d:
            cv2.imwrite(f"{d}/o.png", small)
            out = subprocess.run(["tesseract", f"{d}/o.png", "stdout", "--psm", "3"], capture_output=True, text=True,
                                 env=dict(os.environ, TESSDATA_PREFIX=tessdata, OMP_THREAD_LIMIT="1")).stdout
        return len(re.findall(r"[A-Za-zÀ-ÿ]{3,}", out))

    turns = [img, cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE), cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)]
    return max(turns, key=words)


def load_pages(path):
    if path.endswith(".pdf"):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["pdftoppm", "-r", "170", "-png", path, f"{d}/p"], check=True, capture_output=True)
            return [cv2.imread(p) for p in sorted(glob.glob(f"{d}/p*.png"))][:6]
    img = cv2.imread(path)
    return [img] if img is not None else []


def fit(img):
    h, w = img.shape[:2]
    s = MAX_SIDE / max(h, w)
    return cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else img


class Geometry:
    """How a photo bends the page: margin on a table, tilt, perspective, curl. Applied to word positions (points)."""

    def __init__(self, w, h):
        self.w, self.h, self.ops = w, h, []

    def shift(self, m):
        self.ops.append(("shift", m))

    def rotate(self, deg):
        cx, cy = self.w / 2, self.h / 2
        self.ops.append(("affine", cv2.getRotationMatrix2D((cx, cy), deg, 1.0)))

    def perspective(self, rng, k):
        w, h = self.w, self.h
        j = lambda: rng.uniform(0, k) * min(w, h)
        src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
        dst = np.float32([[j(), j()], [w - j(), j()], [w - j(), h - j()], [j(), h - j()]])
        self.ops.append(("persp", cv2.getPerspectiveTransform(src, dst)))

    def curl(self, amp):
        self.ops.append(("curl", amp))

    def point(self, x, y):
        for op, a in self.ops:
            if op == "shift":
                x, y = x + a, y + a
            elif op == "affine":
                x, y = a[0][0] * x + a[0][1] * y + a[0][2], a[1][0] * x + a[1][1] * y + a[1][2]
            elif op == "persp":
                d = a[2][0] * x + a[2][1] * y + a[2][2]
                x, y = (a[0][0] * x + a[0][1] * y + a[0][2]) / d, (a[1][0] * x + a[1][1] * y + a[1][2]) / d
            elif op == "curl":
                y = y - a * math.sin(math.pi * x / self.w) * (0.6 + 0.4 * y / self.h)
        return x, y


def shade(img, rng):
    h, w = img.shape[:2]
    gx = np.linspace(rng.uniform(0.62, 0.8), 1.0, w, dtype=np.float32)
    if rng.random() < 0.5:
        gx = gx[::-1]
    out = img.astype(np.float32) * gx[None, :, None] * rng.uniform(0.85, 0.97)
    return np.clip(out, 0, 255).astype(np.uint8)


def blur(img, sigma):
    return cv2.GaussianBlur(img, (0, 0), sigma)


def motion(img, n):
    k = np.zeros((n, n), np.float32)
    k[n // 2, :] = 1.0 / n
    return cv2.filter2D(img, -1, k)


def jpeg(img, q):
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def lowres(img, f):
    h, w = img.shape[:2]
    return cv2.resize(cv2.resize(img, (int(w * f), int(h * f)), interpolation=cv2.INTER_AREA), (w, h), interpolation=cv2.INTER_LINEAR)


def variant(img, name, rng):
    """(image to read, geometry). Light, blur and resolution damage the image itself; tilt, perspective and curl move
    the word positions afterwards, as a phone OCR reads a crooked photo: the right words in crooked places."""
    h, w = img.shape[:2]
    g = Geometry(w + 2 * int(0.06 * max(h, w)), h + 2 * int(0.06 * max(h, w)))
    if name == "as-is":
        return img, None
    g.shift(int(0.06 * max(h, w)))
    if name == "crooked":
        g.rotate(rng.choice([-1, 1]) * rng.uniform(2.0, 5.0)); g.perspective(rng, 0.05)
        return jpeg(img, 70), g
    if name == "curled":
        g.rotate(rng.uniform(-2, 2)); g.curl(rng.uniform(0.012, 0.025) * g.h)
        return jpeg(img, 70), g
    if name == "blurred":
        g.rotate(rng.uniform(-1.5, 1.5))
        return jpeg(blur(img, rng.uniform(1.1, 1.7)), 60), g
    if name == "dark":
        g.rotate(rng.uniform(-2, 2))
        return jpeg(shade(img, rng), 60), g
    if name == "worst":  # everything at once, a bad photo in a kitchen
        g.rotate(rng.choice([-1, 1]) * rng.uniform(2.5, 6.0)); g.perspective(rng, 0.06); g.curl(rng.uniform(0.01, 0.02) * g.h)
        y = shade(img, rng)
        y = motion(y, 3) if rng.random() < 0.5 else blur(y, rng.uniform(0.9, 1.3))
        return jpeg(lowres(y, rng.uniform(0.7, 0.85)), 50), g
    raise ValueError(name)


# ---------------------------------------------------------------- OCR in the app's report format


def moved(box, g):
    """An axis-aligned box after the page was bent (what the phone OCR reports for a crooked word)."""
    if g is None:
        return box
    l, t, r, b = box
    pts = [g.point(x, y) for x, y in ((l, t), (r, t), (r, b), (l, b))]
    return int(min(p[0] for p in pts)), int(min(p[1] for p in pts)), int(max(p[0] for p in pts)), int(max(p[1] for p in pts))


def ocr_lines(img, lang, tessdata, g=None):
    with tempfile.TemporaryDirectory() as d:
        p = f"{d}/i.png"
        cv2.imwrite(p, img)
        env = dict(os.environ, TESSDATA_PREFIX=tessdata, OMP_THREAD_LIMIT="1")
        tsv = subprocess.run(["tesseract", p, "stdout", "-l", lang, "--psm", "3", "-c", "tessedit_create_tsv=1"], capture_output=True, text=True, env=env).stdout
    groups = {}
    for row in tsv.splitlines()[1:]:
        c = row.split("\t")
        if len(c) < 12 or c[0] != "5" or not c[11].strip() or float(c[10]) < 0:
            continue
        key = (c[2], c[3], c[4])
        l, t, w, h = map(int, c[6:10])
        groups.setdefault(key, []).append((l, t, l + w, t + h, c[11].strip()))
    # Word boxes moved with the page; the line's angle from its words' bottom edges after the move.
    groups = {k: [(*moved(w[:4], g), w[4]) for w in ws] for k, ws in groups.items()}
    # The phone OCR returns a table row as separate pieces per column ("BISCOTTI..." and "3,45" apart): split at wide gaps.
    pieces = []
    for words in groups.values():
        words.sort()
        hgt = sorted(w[3] - w[1] for w in words)[len(words) // 2]
        cur = [words[0]]
        for w in words[1:]:
            if w[0] - cur[-1][2] > 2.2 * max(hgt, 8):
                pieces.append(cur); cur = [w]
            else:
                cur.append(w)
        pieces.append(cur)
    lines = []
    for words in pieces:
        L, T, R, B = min(w[0] for w in words), min(w[1] for w in words), max(w[2] for w in words), max(w[3] for w in words)
        angle = 0.0
        if len(words) >= 3 and R - L > 200:
            xs = np.array([(w[0] + w[2]) / 2 for w in words]); ys = np.array([w[3] for w in words])
            angle = math.degrees(math.atan(np.polyfit(xs, ys, 1)[0]))
        text = " ".join(w[4] for w in words)
        boxes = " ".join(f"{w[0]}-{w[2]}:{w[4]}" for w in words)
        lines.append(f"{L},{T},{R},{B},{angle:.1f} | {text}" + (f"  [{boxes}]" if len(words) > 1 else ""))
    return lines


def run_one(job):
    c, name, out, tessdata = job
    target = f"{out}/{c['id']}__{name}.txt"
    if os.path.exists(target):
        return target
    rng = random.Random(zlib.crc32(f"{c['id']}|{name}".encode()))
    pages = []
    for path in c["images"]:
        pages += [p if path.endswith(".pdf") else upright(p, tessdata) for p in load_pages(path)]
    parts = ["Kitchen Receipts – recognised text", f"Engine: tesseract ({c['lang']}) · variant: {name}"]
    for i, page in enumerate(pages):
        img, g = variant(fit(page), name, rng)
        parts.append(f"\n=== Raw lines, page {i + 1} (left,top,right,bottom,angle) ===")
        parts += ocr_lines(img, c["lang"], tessdata, g)
    open(target, "w").write("\n".join(parts) + "\n")
    return target


def main():
    root, out = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    limit = int(args[args.index("--limit") + 1]) if "--limit" in args else 100
    variants = (args[args.index("--variants") + 1] if "--variants" in args else "as-is,crooked,worst").split(",")
    os.makedirs(f"{out}/img", exist_ok=True)
    cases = invoice2data(root) + sroie(root, limit) + eoxia(root) + invoice_dataset(root, max(1, limit // 9))
    for c in cases:
        with open(f"{out}/{c['id']}.truth", "w") as f:
            for k, v in c["truth"].items():
                if k == "lines":
                    for l in v:
                        f.write("line=" + json.dumps(l, ensure_ascii=False) + "\n")
                else:
                    f.write(f"{k}={v}\n")
    tessdata = f"{root}/tessdata_fast"
    jobs = [(c, v, out, tessdata) for c in cases for v in variants]
    print(f"{len(cases)} documents x {len(variants)} variants = {len(jobs)} readings", flush=True)
    done = 0
    with ProcessPoolExecutor(max_workers=os.cpu_count() or 2) as ex:
        for _ in ex.map(run_one, jobs):
            done += 1
            if done % 25 == 0:
                print(f"{done}/{len(jobs)}", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
