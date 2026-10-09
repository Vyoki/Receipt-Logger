# Training the document reader

The app's AI (Qwen3-VL 2B, on the phone) is trained on exactly the questions the app asks it, in Italian and English.
Nothing real is used: every document is invented.

| Step | What | Where |
|---|---|---|
| 1 | Invent supplier documents as phone photos, with what the phone's OCR would read and the truth | `gen_docs.py` |
| 2 | Run the app's own reading, question planner, question loop and prompts on them, answering from the truth | `exporter/TrainingExport.kt`, `export.sh` |
| 3 | Cut each question's picture as the phone does, write the chats | `build_dataset.py` |
| 4 | Train, compare with today's model, convert for the phone, upload | `train_colab.ipynb` (Google Colab, free T4 GPU) |

**What the model learns** (core `AiTasks`):
- **Look:** one question about one area — a product line (`ROW`), one number (`AMOUNT`, `QTY`), two readings that both add
  up (`CHOICE`), the supplier's box (`SUPPLIER`), the totals (`TOTALS`), number and date (`HEADER`), the column headings
  (`COLUMNS`). Short prompts: the phone reads them several times faster than the long instructions.
- **Correct itself:** a line asked again one number at a time, with its earlier answer and why it did not add up (`PREV:`).
- **Decide:** which open question to ask next, from a short summary of the document (`NEXT`, no picture). The right
  choice in training is the question that proves the most when answered (tried on copies of the app's loop).
- **Say what it cannot read:** `null` for an empty column, `X` for a number that is not there; never calculate.

**Documents** (`gen_docs.py`): invoices, delivery notes, deferred invoices printed from the e-invoice, cash & carry
receipts, handwritten delivery notes; Italian and English; heading synonyms (IMPORTO / PREZZO TOTALE / TOTALE / AMOUNT …),
headings on two lines, any column order, discounts (10+5), lots, discount and charge lines, supplier boxes and
letterheads, totals as lines or grids; photos tilted, in perspective, shadowed, blurred, noisy; the OCR's usual slips
(0/O, 1/l, a lost decimal comma, a table rule read as "|"). Handwriting fonts are downloaded in Colab (Google Fonts).

**The app** uses the trained model when its file name contains `kitchen` (`kitchen-reader-2b-Q4_K_M.gguf`): short
prompts, and the model chooses what to look at next; the arithmetic still decides every value.

Local run (small):

    python3 tools/training/gen_docs.py /tmp/docs --count 20
    tools/training/export.sh /tmp/docs /tmp/samples.jsonl
    python3 tools/training/build_dataset.py /tmp/samples.jsonl /tmp/docs /tmp/ds
