#!/usr/bin/env python3
"""
Turns the exporter's samples (tools/training/exporter) into a training set: each sample's picture made exactly as the
phone makes it (the areas cut from the page photo, scaled like the app scales them, stacked top to bottom with an 8 px
white gap; a 28 px blank square for the agent's choice, which has no picture), and the chat it trains:

  user: <image>\\n<prompt>        assistant: <answer>

  python3 tools/training/build_dataset.py SAMPLES.jsonl DOCS_DIR OUT_DIR [--eval-share 0.05]

OUT_DIR gets images/, train.jsonl and eval.jsonl (documents never split between the two).
"""
import argparse
import hashlib
import json
import os

from PIL import Image

MAX_SIDE = 1536  # core AiImagePlan.MAX_SIDE
GAP = 8          # app AiPageReader.stack


def stack(page, boxes, scale):
    pieces = []
    W, H = page.size
    for l, t, r, b in boxes:
        l = max(0, min(l, W - 1)); t = max(0, min(t, H - 1))
        r = max(l + 1, min(r, W)); b = max(t + 1, min(b, H))
        crop = page.crop((l, t, r, b))
        s = min(scale, MAX_SIDE / crop.width, 1.0)
        if s < 1.0:
            crop = crop.resize((max(1, int(crop.width * s)), max(1, int(crop.height * s))), Image.BILINEAR)
        pieces.append(crop)
    w = max(p.width for p in pieces)
    h = sum(p.height for p in pieces) + GAP * (len(pieces) - 1)
    out = Image.new("RGB", (w, h), "white")
    y = 0
    for p in pieces:
        out.paste(p, (0, y))
        y += p.height + GAP
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("samples")
    ap.add_argument("docs")
    ap.add_argument("out")
    ap.add_argument("--eval-share", type=float, default=0.05)
    ap.add_argument("--quality", type=int, default=88)
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    blank = Image.new("RGB", (28, 28), "white")
    blank_path = "images/blank.png"
    blank.save(os.path.join(a.out, blank_path))
    pages = {}
    counts = {"train": 0, "eval": 0}
    with open(a.samples) as f, open(os.path.join(a.out, "train.jsonl"), "w") as tr, open(os.path.join(a.out, "eval.jsonl"), "w") as ev:
        for n, line in enumerate(f):
            s = json.loads(line)
            split = "eval" if int(hashlib.md5(s["doc"].encode()).hexdigest(), 16) % 1000 < a.eval_share * 1000 else "train"
            if s["image"] is None:
                img_path = blank_path
            else:
                if s["doc"] not in pages:
                    if len(pages) > 8:
                        pages.clear()
                    pages[s["doc"]] = Image.open(os.path.join(a.docs, s["doc"], s["image"])).convert("RGB")
                img = stack(pages[s["doc"]], s["boxes"], s["scale"])
                img_path = f"images/{n:07d}.jpg"
                img.save(os.path.join(a.out, img_path), quality=a.quality)
            rec = {
                "image": img_path,
                "messages": [
                    {"role": "user", "content": [{"type": "image"}, {"type": "text", "text": "\n" + s["prompt"]}]},
                    {"role": "assistant", "content": [{"type": "text", "text": s["answer"]}]},
                ],
                "kind": s["kind"], "style": s["style"], "lang": s["lang"], "doc": s["doc"], "grammar": s.get("grammar"),
            }
            (ev if split == "eval" else tr).write(json.dumps(rec, ensure_ascii=False) + "\n")
            counts[split] += 1
    print(f"train {counts['train']}, eval {counts['eval']} -> {a.out}")


if __name__ == "__main__":
    main()
