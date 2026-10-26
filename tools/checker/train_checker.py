#!/usr/bin/env python3
"""Trains the app's checker model from the cases the app records (Settings > "Teach the checker").

Each case is one value of an invoice the operator checked and saved: how it was read (flagged or not, its source,
what the document's sums say) and whether the operator kept it (core CheckerCases). Numbers only: the file holds no
name, number, amount or text of a document.

The model (boosted decision trees, scikit-learn) gives the chance that a value is right. It may take a FLAGGED value as
checked only above a threshold chosen on invoices it did not learn from, so that none of their wrong values would have
been cleared; it is "ready" for the phone only when, on a further set of unseen invoices, it cleared no wrong value and
let no invoice with a wrong value save by itself. Invoices are split whole: the values of one invoice are never both
learned from and tested on.

    python3 train_checker.py kitchen-receipts-checker.zip [more.zip|cases.csv ...] --out checker.json
"""
import argparse
import csv
import io
import json
import math
import random
import sys
import zipfile

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier

HEADER = ["group", "kind", "src", "right"]
MIN_DOCS = 200          # invoices needed before a model can be ready
MIN_TEST_DOCS = 40      # invoices in the final test
MIN_THRESHOLD = 0.95    # never clear a value the model is less sure of


def read_rows(paths):
    """The cases of every file (zips from the app, or csv files), all with the same features."""
    features, rows = None, []

    def take(name, text):
        nonlocal features
        r = csv.reader(io.StringIO(text))
        head = next(r, None)
        if not head or head[:4] != HEADER:
            sys.exit(f"{name}: not a checker file (header {head[:4] if head else None})")
        if features is None:
            features = head[4:]
        elif head[4:] != features:
            sys.exit(f"{name}: other features than the first file (a different app version); train them apart")
        for line in r:
            if len(line) == len(head):
                rows.append(line)

    for p in paths:
        if p.endswith(".zip"):
            with zipfile.ZipFile(p) as z:
                for n in sorted(z.namelist()):
                    if n.startswith("cases-v") and n.endswith(".csv"):
                        take(f"{p}:{n}", z.read(n).decode("utf-8"))
        else:
            with open(p, encoding="utf-8") as f:
                take(p, f.read())
    if not rows:
        sys.exit("no cases found")
    return features, rows


def matrix(features, rows, kinds, srcs):
    """Numbers as recorded, then the field's kind and source one-hot ("kind=amount", "src=proven")."""
    names = list(features) + [f"kind={k}" for k in kinds] + [f"src={s}" for s in srcs]
    x = np.zeros((len(rows), len(names)), dtype=np.float64)
    for i, r in enumerate(rows):
        x[i, :len(features)] = [float(v) for v in r[4:]]
        if r[1] in kinds:
            x[i, len(features) + kinds.index(r[1])] = 1.0
        if r[2] in srcs:
            x[i, len(features) + len(kinds) + srcs.index(r[2])] = 1.0
    return names, x


def split_groups(groups, seed):
    """60% of the invoices to learn from, 20% to choose the threshold, 20% for the final test."""
    unique = sorted(set(groups))
    random.Random(seed).shuffle(unique)
    n = len(unique)
    a, b = int(n * 0.6), int(n * 0.8)
    part = {g: 0 for g in unique[:a]}
    part.update({g: 1 for g in unique[a:b]})
    part.update({g: 2 for g in unique[b:]})
    return np.array([part[g] for g in groups])


def export_trees(model):
    """The trees as the phone reads them (core CheckerModel): left when value <= threshold, feature -2 at a leaf."""
    out = []
    for est in model.estimators_[:, 0]:
        t = est.tree_
        out.append({
            "f": [int(v) for v in t.feature],
            "t": [float(v) for v in t.threshold],
            "l": [int(v) for v in t.children_left],
            "r": [int(v) for v in t.children_right],
            "v": [float(v) for v in t.value[:, 0, 0]],
        })
    return out


def base_score(model):
    p = float(model.init_.class_prior_[1])
    return math.log(p / (1 - p))


def chance(trees, base, rate, x):
    """The phone's arithmetic (to check that it gives exactly what scikit-learn gives)."""
    s = base
    for t in trees:
        n = 0
        while t["f"][n] >= 0:
            n = t["l"][n] if x[t["f"][n]] <= t["t"][n] else t["r"][n]
        s += rate * t["v"][n]
    return 1 / (1 + math.exp(-s))


def doc_stats(groups, flagged, wrong, cleared, mask):
    """Invoices that would save by themselves (every flagged value cleared), and those of them with a wrong value."""
    saves, wrong_saves = set(), set()
    by = {}
    for i in np.where(mask)[0]:
        by.setdefault(groups[i], []).append(i)
    for g, idx in by.items():
        if all(cleared[i] for i in idx if flagged[i]) and any(flagged[i] for i in idx):
            saves.add(g)
            if any(wrong[i] for i in idx):
                wrong_saves.add(g)
    return len(saves), len(wrong_saves)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--out", default="checker.json")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--min-docs", type=int, default=MIN_DOCS)
    ap.add_argument("--trees", type=int, default=200)
    ap.add_argument("--depth", type=int, default=3)
    a = ap.parse_args()

    features, rows = read_rows(a.inputs)
    groups = [r[0] for r in rows]
    kinds = sorted({r[1] for r in rows})
    srcs = sorted({r[2] for r in rows})
    names, x = matrix(features, rows, kinds, srcs)
    y = np.array([int(r[3]) for r in rows])
    flagged = x[:, names.index("uncertain")] == 1.0
    wrong = y == 0
    part = split_groups(groups, a.seed)
    docs = len(set(groups))
    print(f"{docs} invoices, {len(rows)} values ({int(flagged.sum())} flagged, {int(wrong.sum())} corrected)")
    train, val, test = part == 0, part == 1, part == 2
    if len(set(y[train])) < 2:
        sys.exit("the invoices to learn from have no corrected value (or nothing but): nothing to learn yet")

    model = GradientBoostingClassifier(n_estimators=a.trees, max_depth=a.depth, learning_rate=0.1, subsample=0.8, random_state=a.seed)
    model.fit(x[train], y[train])
    p = model.predict_proba(x)[:, 1]

    # The threshold: above every wrong flagged value of the threshold invoices, with a margin, never under 0.95.
    wrong_val = p[val & flagged & wrong]
    top = float(wrong_val.max()) if len(wrong_val) else 0.0
    threshold = max(MIN_THRESHOLD, top + 0.25 * (1 - top))
    cleared = flagged & (p >= threshold)

    def stats(mask):
        saves, wrong_saves = doc_stats(groups, flagged, wrong, cleared, mask)
        return {
            "docs": len({groups[i] for i in np.where(mask)[0]}),
            "flagged": int((mask & flagged).sum()),
            "flaggedWrong": int((mask & flagged & wrong).sum()),
            "cleared": int((mask & cleared).sum()),
            "clearedWrong": int((mask & cleared & wrong).sum()),
            "wouldSave": saves,
            "wrongSaves": wrong_saves,
        }

    s_val, s_test = stats(val), stats(test)
    ready = (docs >= a.min_docs and s_test["docs"] >= MIN_TEST_DOCS and s_val["clearedWrong"] == 0
             and s_test["clearedWrong"] == 0 and s_test["wrongSaves"] == 0 and s_test["cleared"] > 0)

    trees = export_trees(model)
    base, rate = base_score(model), float(model.learning_rate)
    # The phone must give the same chances as scikit-learn.
    for i in range(min(len(rows), 2000)):
        q = chance(trees, base, rate, x[i])
        if abs(q - p[i]) > 1e-9:
            sys.exit(f"the exported trees do not give scikit-learn's chances (row {i}: {q} != {p[i]})")
    samples = []
    for i in list(np.where(test)[0][:4]) + list(np.where(test & flagged)[0][:4]):
        samples.append({"kind": rows[i][1], "src": rows[i][2], "features": dict(zip(features, (float(v) for v in rows[i][4:]))),
                        "chance": float(p[i])})

    out = {
        "version": 1,
        "features": names,
        "base": base,
        "rate": rate,
        "trees": trees,
        "threshold": threshold,
        "ready": bool(ready),
        "stats": {"invoices": docs, "values": len(rows), **{f"threshold_{k}": v for k, v in s_val.items()},
                  **{f"test_{k}": v for k, v in s_test.items()}},
        "samples": samples,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f)
    print(f"threshold {threshold:.4f}")
    for label, s in (("threshold invoices", s_val), ("test invoices", s_test)):
        print(f"{label}: {s['docs']} invoices, {s['flagged']} flagged values ({s['flaggedWrong']} wrong): "
              f"{s['cleared']} would be cleared, {s['clearedWrong']} of them wrong; "
              f"{s['wouldSave']} invoices would save by themselves, {s['wrongSaves']} with a wrong value")
    if ready:
        print(f"READY: {a.out} can go on the phone.")
    else:
        why = []
        if docs < a.min_docs:
            why.append(f"{docs} invoices, {a.min_docs} needed")
        if s_test["docs"] < MIN_TEST_DOCS:
            why.append(f"{s_test['docs']} test invoices, {MIN_TEST_DOCS} needed")
        if s_val["clearedWrong"] or s_test["clearedWrong"] or s_test["wrongSaves"]:
            why.append("it would clear wrong values")
        if s_test["cleared"] == 0:
            why.append("it would clear nothing")
        print("NOT READY (" + "; ".join(why) + "): keep checking invoices and train again later.")


if __name__ == "__main__":
    main()
