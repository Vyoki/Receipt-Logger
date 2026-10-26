# The checker

A small model that learns from the operator's own checks which flagged values are in fact right, so that the app can
ask fewer of them. Decision trees (scikit-learn), trained in a minute on a free Google Colab, run on the phone in plain
Kotlin (core `CheckerModel`): no GPU, no network.

| Step | What | Where |
|---|---|---|
| 1 | Each invoice the operator checks and saves adds one line per value: how it was read and whether it was kept | core `CheckerCases`, app `CheckerLog` |
| 2 | The operator sends the file (Settings > "Teach the checker") to themselves | app `CheckerSection` |
| 3 | Train and test on invoices kept apart, write `checker.json` | `train_checker.py`, `train_checker.ipynb` |

**What is in the file:** numbers only (flagged or not, length and digits of the value, what the document's sums say, the
supplier's proof, the line's arithmetic...), the field's kind and a word for its source from a fixed list. No name,
number, amount or text of a document.

**When it is ready:** invoices are split whole (60% to learn from, 20% to choose the threshold, 20% to test). The
threshold is above every wrong flagged value of the threshold invoices, with a margin, never under 0.95. The model is
`ready` only with at least 200 invoices (40 in the test), when on the test invoices it would clear no wrong value and
let no invoice with a wrong value save by itself. The app does not use the model yet: that comes once a ready model
exists, and only with the owner's yes.

Local run:

    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install scikit-learn numpy
    /tmp/venv/bin/python tools/checker/train_checker.py kitchen-receipts-checker.zip --out checker.json
