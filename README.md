# Kitchen Receipts

A native Android app (Kotlin + Jetpack Compose) for managing restaurant supplier receipts and invoices.

You photograph a receipt or invoice, or import an image or PDF. The app reads the text **on the phone**, and you check and correct the values it found. Purchases are then organised by seller and month, with prices, lot numbers and weighted average costs per product.

Everything is stored locally in a Room database, so the app works fully offline. It needs no account, no API key and no runtime permissions. Nothing about the documents ever leaves the phone; Settings ▸ Network decides whether public knowledge may come in (Offline by default).

---

## 1. Requirements

| What | Version |
|---|---|
| Android Studio | Ladybug (2024.2) or newer. It includes the JDK. |
| Android SDK | Platform 35. Android Studio offers to install it on first sync. |
| Phone | Android 8.0 (API 26) or newer |
| Internet on your computer | Needed on the first build, to download Gradle 8.10.2, the Android Gradle Plugin and libraries |

Project versions: AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Room 2.6.1, and ML Kit Text Recognition 16.0.1 (bundled model). All versions are listed in `gradle/libs.versions.toml`.

## 2. Open the project in Android Studio

1. Start Android Studio and choose **File ▸ Open…** (or **Open** on the welcome screen).
2. Select the `KitchenReceipts` folder: the one that contains `settings.gradle.kts`. Click **OK**.
3. If asked, click **Trust Project**.
4. Wait for **Gradle sync** to finish (progress bar at the bottom). The first time can take several minutes.
   - If Android Studio says *"SDK Platform 35 is not installed"*, click the **Install** link in that message.
   - If it offers to upgrade the Android Gradle Plugin, you can click **Don't ask for this project**. The pinned versions work.
5. When sync succeeds, the run configuration **app** appears in the toolbar.

## 3. Run it on an Android phone

1. On the phone, enable **Developer options**: go to **Settings ▸ About phone** and tap **Build number** 7 times.
2. Go to **Settings ▸ System ▸ Developer options** and turn on **USB debugging**.
   - On Xiaomi phones, also turn on **Install via USB**.
3. Connect the phone with a USB cable. On the phone, accept **Allow USB debugging?** (tick *Always allow from this computer*).
4. In Android Studio, pick the phone in the device drop-down in the toolbar, then click **Run ▶** (Shift+F10).
5. The app installs and opens as **Kitchen Receipts**.

Wireless option (Android 11+): in Developer options, turn on **Wireless debugging**. Then in Android Studio use **Device Manager ▸ Pair devices using Wi-Fi** and scan the QR code.

### Permissions to grant

**None.** The app does not ask for any runtime permission, by design:

| Feature | How it works | Permission |
|---|---|---|
| Take a photo | Opens the phone's own camera app (`ACTION_IMAGE_CAPTURE`), which saves into a file the app owns | None. The app deliberately does **not** declare `CAMERA`. |
| Import image/PDF | System document picker (Storage Access Framework) | None. No storage permission is needed. |
| OCR | On-device ML Kit model bundled in the APK | None. Reading never uses the internet. |
| Network (optional) | Downloading the public knowledge pack; product lookup | `INTERNET` and `ACCESS_NETWORK_STATE` (install-time, not asked). Used only in Hybrid/Automatic mode, see 7b-7. |
| Open original / export CSV | `FileProvider` share and the system "Save as" dialog | None |

The first time you take a photo, the **camera app** may ask for its own camera permission. That request comes from the camera app, not from Kitchen Receipts. Allow it.

## 4. Run the tests

**Fast JVM tests** (parsing, money, averages, duplicates, CSV, validation; no phone needed):

```bash
./gradlew :core:test            # Windows: gradlew.bat :core:test
```

In Android Studio you can also right-click `core/src/test/kotlin` and choose **Run 'Tests in kotlin'**. The report is written to `core/build/reports/tests/test/index.html`.

**Instrumented tests** (Room migrations 1/2/3/4→5, the repository and on-device OCR; they run on a phone or emulator):

```bash
./gradlew :app:connectedDebugAndroidTest
```

Connect a phone (USB debugging on) or start an emulator first. The report is written to `app/build/reports/androidTests/connected/`.

## 5. Build a debug APK

```bash
./gradlew :app:assembleDebug
```

The APK is written to **`app/build/outputs/apk/debug/app-debug.apk`**.
From Android Studio you can instead use **Build ▸ Build App Bundle(s) / APK(s) ▸ Build APK(s)**, then click **locate** in the notification.

To install it:

- over USB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- or copy the file to the phone, open it, and allow **Install unknown apps** for the file manager when asked.

The debug APK is signed with the debug key. To distribute it, create a signed release build (**Build ▸ Generate Signed App Bundle / APK**).

---

## 6. Using the app (kitchen workflow)

1. **Home**: the big **Scan receipt or invoice** button, tiles for Documents, Sellers, Products and Monthly reports, and the latest documents.
2. **Capture**: tap **Take a photo**. For a long receipt or a multi-page invoice, tap **Add another page** for each part; the photos are saved together as one PDF. Or tap **Import image or PDF**; multi-page PDFs are supported, and the first 20 pages are read automatically.
3. **Check and save**: the original is at the top (tap it for full screen with pinch-zoom). Below it are the editable fields.
   - **Orange** fields were read with low confidence. Each one shows the text it was read from. Correct it, or tap **✓** if it is right.
   - **Amber** fields are **missing**. Nothing is ever guessed, so type the value only if it is printed.
   - For each line item: original description, **Assign product**, quantity, unit (one-tap `kg / pz / conf / l`), unit price, line total, lot, VAT %, and expiry (kept separate from the lot).
   - If the line total is missing, a **Use qty × price = …** chip offers the computed value. It is only applied if you tap it.
   - Live check: the sum of the line totals is compared with the subtotal and total.
   - **Save** validates the fields, then warns about **possible duplicates** and about values that are still unchecked, before anything is written.
4. **Documents**: search by seller, number, product description or lot. Filter by seller, month or a specific day. Open a document to see its original and its line items, edit it, delete it, or open the original in another app.
5. **Products**: descriptions that have no product yet are listed at the top; assign them in bulk. Each product shows its weighted average cost, with its unit, VAT basis and number of purchases. The detail screen shows price history (each row links to its source document), unit conversions and remembered descriptions.
6. **Sellers**: document count, total spent and last document date. Tap a seller to see their documents.
7. **Monthly reports**: spending grouped by calendar month and seller. Export as **CSV**: *Excel Italia* (`;` separators and decimal comma) or standard (`,` and `.`). Two exports are available: monthly totals, or all purchases line by line.

The UI is in English, and fully in Italian when the phone's language is Italian.

---

## 7. How it works

```
core/   pure Kotlin (no Android): parsing, money, averages, duplicates, reports, CSV, validation  ← unit-tested
app/    Android: Room database, file storage, OCR engine, Compose screens
```

### OCR: what works offline

- The engine is **Google ML Kit Text Recognition v2, Latin script, bundled model** (`com.google.mlkit:text-recognition`). The model ships inside the APK, which adds about 4 MB per CPU architecture. It runs entirely on the phone: it works in airplane mode from the first launch, needs no Google account, costs nothing and has no API key.
- **Everything else is also offline**: parsing, database, reports, CSV export and PDF rendering (the Android `PdfRenderer`).
- ML Kit returns table columns as separate blocks, so `LayoutRows` (in core) rebuilds visual rows from the text bounding boxes before parsing.
- PDFs are rendered to images and then OCR'd. Text embedded in a PDF is not extracted directly in this MVP.
- **Realistic expectations**: printed invoices and delivery notes (DDT) read well. Faded thermal receipts, crumpled paper, strong shadows and handwriting read poorly. The review screen is built for exactly that case: the original is always next to the fields, and everything is editable.

OCR is behind the `OcrEngine` interface (`app/.../ocr/OcrEngine.kt`). To change engine, implement it and replace the single line `val ocrEngine: OcrEngine = MlKitOcrEngine()` in `KitchenReceiptsApp.kt`.

### Adding an online OCR provider (optional, not included)

- **Never put the provider's API key in the app**: anyone can extract it from an APK. Instead:
  1. Run a small backend of your own that holds the key, authenticates your app's users, and forwards the image to the provider.
  2. Write a `RemoteOcrEngine : OcrEngine` that uploads the page bitmap to **your** backend and maps the response to `OcrLine`s (text plus bounding box).
  3. Add `<uses-permission android:name="android.permission.INTERNET" />`.
  4. Make it **opt-in** (for example a setting), and fall back to `MlKitOcrEngine` when the phone is offline or the call fails.

### Parsing rules (Italian documents)

- **Amounts**: `1.234,56`, `12,50`, `€ 3,90`, `EUR 71,06`, `12,00-` (credit). They are parsed exactly with `BigDecimal`, never as floating point.
- **Dates**: always day-first: `14/03/2025`, `14-03-25`, `14.03.2025`, `2025-03-14`, `3 aprile 2025`, `1 dic. 2025`.
- **Seller**: a line with a company form (`S.r.l.`, `S.p.A.`, `s.n.c.`, `S.a.s.`, `soc. coop.`) is used with high confidence. Otherwise the first plausible header line is used with **low** confidence. The customer block (`Spett.le`, `Cliente`, `Destinatario`) is skipped.
- **Document number**: after `Fattura n.`, `DDT n.`, `Documento n.`, `Doc.N.`, `Scontrino n.`, and similar labels.
- **Totals**: `Imponibile` / `Totale merce` (subtotal), `IVA` / `Totale IVA` / `di cui IVA` (VAT, summed across rates), `Totale documento` / `Totale fattura` / `Totale complessivo` / `Netto a pagare` (total). Lines such as `P.IVA` and `C.F.` are never read as VAT.
- **Lots**: only after an explicit label: `Lotto`, `Lot`, `Lot.`, `N. lotto`, `Batch`.
  - **Expiry labels** (`Scad.`, `Scadenza`, `Exp`, `TMC`, `Da consumarsi (preferibilmente) entro`, `Best before`, `BB`) are matched **first**, and what they label is stored as expiry, never as a lot.
  - A "lot" value that is itself a date is rejected, and a warning is shown.
  - A bare `L.` is **not** accepted as a lot label, because on Italian documents it also means litres.
  - A lot or expiry on its own line is attached to the item line above it.
- **Line items**: `description [unit] qty price total [VAT%]`. Variants such as `2 x 1,20`, `1L`, `kg 2,500` and a price without a quantity are also handled. The printed total is kept even when qty × price does not match; the line is flagged instead.
- **VAT basis**: taken from explicit wording (`IVA esclusa`, `+ IVA`, `IVA inclusa`, `di cui IVA`…). Otherwise it is inferred from the arithmetic (line sum = subtotal → excluded; line sum = total → included) with low confidence. If neither applies, it is **unknown**.
- **Nothing is invented**: a missing field stays empty and is shown as missing. Currency is filled only if `€`, `EUR` or `euro` appears in the text, with a one-tap **Set EUR** chip otherwise.

### Money and cost rules

- Money is stored as **integer cents** (`Long`). Quantities, unit prices and conversion factors are stored as exact decimal text (`BigDecimal`). There are no `REAL` columns.
- **Weighted average unit cost = total cost of the purchases ÷ total quantity**. It is shown with 4 decimals, rounded half-even.
- **Averages are grouped by unit family and VAT basis**:
  - VAT-included, VAT-excluded and VAT-unknown prices each get their own average.
  - `g` is converted to `kg` and `ml`/`cl` to `l` automatically, because they measure the same quantity.
  - Pieces, packs, cartons, bottles and similar are **never** combined with each other or with kg/l unless the user adds a conversion for that product (for example `1 conf = 6 pz`).
- Each average shows **how many purchases contribute** to it. Purchases without a quantity, unit or line total are excluded and counted with the reason.
- Monthly spending uses document totals as printed. Documents without a total are counted but not added, and the report says how many there are.

### Products and duplicates

- **Linking lines to products** happens without asking only when it is safe (Settings ▸ *Less typing* can turn it off):
  - what you linked before for that supplier, first by article code and then by exact description;
  - a product recognised despite a typo, misreading or abbreviation: `SALE MARINO GROSOS` → *Sale marino grosso*, `POM. PELATI` → *Pomodori pelati*, plurals such as `MOZZARELLE` → *Mozzarella*;
  - otherwise a **new product** named after the description, created when the document is saved.

  Every automatic link is shown on the review screen ("Recognised despite a different spelling", "First purchase") and can be changed with one tap.
- **Similar but different products are never merged**:
  - typos are tolerated only in words of 6+ letters with the same first letter;
  - two different food words are never treated as a typo of each other (`pollo`/`polpo`, `bovina`/`ovina`, `pane`/`panettone`);
  - different pack sizes (500 g vs 1 kg) are different products;
  - when two products are almost equally close, the app does not choose.

  If duplicates were created earlier, the Inventory screen lists pairs that look alike. You merge them only if you agree (Merge / Different); the product screen also has *Merge into another product*.
- **Duplicate warning** before saving. It triggers on:
  - the same file bytes (SHA-256);
  - the same seller and document number (`FT 0145/2025` = `ft-145-2025`);
  - the same seller, date and total;
  - or, as a weaker hint, the same date and total from a different seller.

  You can still save, for example for two genuinely identical deliveries.
- Seller names are matched ignoring case, punctuation and legal form (`Caseificio Valverde S.r.l.` = `CASEIFICIO VALVERDE SRL`), so one seller is not split into several.

### Database and migrations

- Room database `kitchen_receipts.db`, currently **schema version 4**:
  - v1 had sellers, documents, products, line_items and product_aliases.
  - v2 added `unit_conversions` (`MIGRATION_1_2`).
  - v3 added supplier learning: `sellers.vat_number`, `sellers.header_profile` and `seller_aliases` (`MIGRATION_2_3`).
  - v4 added `products.category` for the inventory (`MIGRATION_3_4`).
- There is no destructive fallback: a missing migration fails loudly in testing instead of wiping data.
- `MigrationTest` builds real v1, v2 and v3 files with data, migrates each to v4, and lets Room validate the resulting schema against the entities.
- Room exports schema JSON to `app/schemas/` on build. Commit that folder, and add a migration plus a test for every future schema change.
- Every line item has a non-null `document_id` foreign key to its source document (`ON DELETE CASCADE`).
- Originals are copied into app-private storage (`files/documents/`), so a saved record always shows its original, even if the source file is deleted from the phone.
- Android backup and device transfer are disabled (see 7b): data stays on the phone unless you export or share it.

---

## 7b. Settings, supplier learning, operation log, security

Open **Settings** with the gear icon on the Home screen.

**Language.** Choose *Same as the phone*, *English* or *Italiano*. The app restarts its screen in the new language immediately.

**Your business.** Enter your restaurant's name and VAT number (P.IVA). They appear on every supplier invoice, and the reader will never take them for the supplier.

**Supplier learning (on the phone, from what you save).**
- Each time you save a document, the app learns three things about that supplier:
  - its **VAT number**, checked with the Partita IVA checksum so misread digits are rejected;
  - the **words in its letterhead**;
  - **how the OCR misspelled its name**, if you corrected it.
- On the next scan the supplier is recognised in that order (VAT number, then a corrected spelling, then the letterhead), even if its name is misread. The check screen shows *"Recognised supplier: … (by VAT number)"*. A match made only on the letterhead is marked orange for you to confirm.
- If the supplier's last documents always had the same VAT basis (included/excluded), it is pre-selected and marked to confirm.
- Product assignments are still remembered per supplier and description, as before.

**Operation log.**
- What it records, one line per event, on a low-priority background thread:
  - app start (version, Android version, phone model);
  - each scan (pages, time taken, how many text lines were read, OCR errors);
  - what was found or missing and uncertain;
  - the recognised text (can be switched off);
  - duplicate warnings;
  - on save, **every correction you made** (for example `seller: 'CASEIFICI0' -> 'Caseificio Valverde'`);
  - discards, deletions, exports, and errors including crashes.
- Size is capped at about 1 MB (it rotates).
- **Settings ▸ Share log** sends it through any app you choose. **Clear log** deletes it.

**Privacy and security: nothing about the documents leaves this phone; information may only come in.**
- **Network modes** (Settings ▸ Network, see 7b-7): *Offline* (default) makes no connection at all. *Hybrid* and *Automatic* only download the public knowledge pack and, if separately turned on, search product words. Google's ML Kit usage-statistics sender is removed from the manifest, and CI checks the built APK for it.
- **No cloud backup and no device-to-device copy** (`allowBackup=false` plus data-extraction rules). Keep your own copy with Reports ▸ Export CSV if you need one.
- Data leaves the phone only when you tap **Share** (recognised text, log, original document) or **Export** (CSV).
- **App lock** (optional): asks for fingerprint, face or the screen-lock PIN on opening and after 3 minutes away. It needs a screen lock set up on the phone.
- **Block screenshots** (optional): also hides the app's content in the recent-apps view.
- Data is stored in the app's private storage, which Android encrypts on modern phones.

Database schema is now **version 3** (supplier VAT number, letterhead profile, remembered name spellings). Migrations 1→2→3 keep all existing data. They are tested on an Android emulator in GitHub Actions (`instrumented-tests` job).

## 7b-2. How a scan is read

1. **Pass 1.** The page is read on the phone (ML Kit), with a box for every word.
2. **Rows and columns.**
   - Rows are rebuilt from the boxes, correcting tilt.
   - The **item table is read by columns**: the app finds the heading row (CODICE, COLLI, DESCRIZIONE, U.M., QUANTITÀ, PREZZO, SCONTO, IMPORTO, IVA…) and puts each word under the heading it stands beneath.
   - Each row is lined up on its amount, to correct photos taken at an angle.
   - So colli are stored as **colli** (never in the name), and quantity and price come from their own columns, whatever order the supplier prints them in.
3. **Cross-check.** On every line, quantity × price (less any discount) must equal the amount. The column reading and the plain-text reading are compared, and the one where more lines add up (and add up to the total) is kept.
   - A decimal comma the OCR lost (`3450` for `3,450`) is repaired only when the arithmetic proves it, and is highlighted.
4. **Pass 2 (only if needed).** If something still does not add up (a line, the total, a missing date), the photo is read again after removing shadows and boosting faint print, and the better reading is kept. This takes a few extra seconds.
5. **History.** If a product's price history shows that quantity and price were read the wrong way round (the "quantity" is what it usually costs), they are swapped back.

**Before reading: page flattening.** A photo is flattened like a scanner does: the sheet's corners are found (the largest bright area, clearly brighter than the table) and the sheet is straightened into a rectangle. When that is not clearly safe (white table, page filling the photo, odd shape) the photo is read as it is.

**Logic first.** Common OCR slips are repaired before anything else: a number glued to the date (`20417722/09/2026`), a storage letter glued to the quantity (`24,000c`), Pkgs glued to the name (`3TORTA`), `c`/`o` read inside a pack size (`GR.12c0`). A line that still does not add up is solved from its own printed numbers (every way they can be quantity × price = amount):
- one reading fits: the line is proven, no AI and no question;
- several fit: they are offered as choices; the AI answers one letter (A/B, about 20 s) and the operator confirms with one tap;
- the choice is remembered for that supplier and applied by itself next time.

**AI double-check on every document** (AI mode "Double-check every document", the default when a model is installed): before a document is saved or shown, the AI reads again the header (supplier, number, date), the totals and up to 8 lines (quantities worked out by arithmetic first, then the largest amounts), each as a short question. A value it reads differently is highlighted with both readings and is never cleared by the arithmetic; the review says how many values were checked and which differ. About 1–2 minutes per document on the phone.

**Pack sizes.** "BT LT 1 · 10" is ten 1-litre bottles, "NC GR 750 · 4" four 750 g packs: the count and price stay as printed and the size is kept in its own field (Pack size). On saving, the size becomes the product's unit conversion (1 pz = 750 g), so averages, inventory and group price comparisons are per kg or litre.

**Learning per supplier** (Settings ▸ Learned from you, phone only): remembered choices, and up to 3 confirmed lines shown to the AI as examples with each line question. The AI model itself is never changed.

**Supplier layout** (phone only, from documents you confirm): how each supplier prints its documents — the shape of its document numbers (`99A/99999`: a number in that shape is certain, and found even when the label is misread), lots printed on the row under each item, whether its table reads best by columns, and column headings the app did not know. The next document of that supplier (recognised by its VAT number, or its name) is read with it; the reading without it is kept if it explains the document better.

**Headings the app does not know:** when a supplier is new, its item table was not recognised and the reading does not check out, the AI is asked one short question about the heading row: what each column holds (one letter per heading). The answer is used only if it reads the document better, and is learned for that supplier once you confirm a document read with it. Measured on every CI run (job ai-model-check, "AI column headings report").

**Checking a value:** tapping a field in the review shows the part of the photo it was read from, with the value outlined (new documents).

## 7b-3. Measured, not tuned: the test bench

Every change to the reading is measured on a **test bench** (`core/src/test/kotlin/.../bench`): 440 invented documents
(invoices, delivery notes, cash & carry invoices, shop receipts) in many layouts — supplier left or right of the
customer box or under it, number and date in a heading table or after a label, different column sets and heading
words, lots under items — read clean, with light and with heavy scanner noise (tilt, letters for digits, comma for
dot, lost small numbers), plus invented copies of real documents. Each field is scored the way the operator meets it:

- **sure**: right and not highlighted; **check**: right but highlighted; **fixed**: wrong or missing, and highlighted;
- **SILENT**: wrong and not highlighted — the error that ends up in the books;
- documents the app would **save without review although something is wrong**.

`core/src/test/resources/bench/baseline.txt` holds the floor: a change that makes the totals worse fails the build,
even if it fixes the document it was written for. CI shows the report as "Reading scorecard".

| | before (2 Oct 2026) | now |
|---|---|---|
| fields right | 94.2% | 99.2% |
| fields silently wrong | 0.53% | 0.06% |
| saved without review with an error | 7.2% | 0.2% |
| supplier right | 91% | 100% |
| document number right | 88% | 99.8% |
| lots found | 80% | 100% |

How the reading decides (general rules, no supplier-specific code):
- **Supplier and number by evidence** (`HeaderEvidence`): every candidate is scored (legal form, heads the block that
  ends with its VAT number, outside the customer box, repeated on every page; for numbers: next to or under a
  "numero / n." label, same on every page, not a postcode, phone, VAT number, amount or date). Certain only when the
  evidence is strong and no rival comes close.
- **Scanner slips repaired only with proof** (`LineSolver.slipVariants`): one table of slips (O/0, I/l/1, S/5, B/8,
  7/1, dot for comma, lost comma), tried only when a line does not add up; a repaired value is shown for checking
  unless the VAT summary proves it.
- **Totals checked by arithmetic**: taxable + VAT = total; when one of the three is misread, the lines and the VAT
  summary say which, otherwise all three are shown for checking.
- **Never invented**: a quantity not printed (a shop receipt line with only its amount) stays empty for the operator.

### What the arithmetic cannot prove

quantity × price = amount, the VAT summary and the totals prove the numbers of a line, but not two other things:
- **Which name goes with which numbers.** When a tilted photo reads a line's numbers on the row above its name, the name is marked to be checked. The AI looks at those rows; if the AI is off, the operator does.
- **A lot number.** A lot shaped like the document's other lots but missing their leading letter is marked to be checked. A single digit misread inside a lot (5 for 6) cannot be detected from a photo. For full traceability, e-invoices (XML) are exact.

Every shared report starts with the app build ("App: 0.1.0+abc1234"), so a problem can be traced to the version that read it.

## 7b-4. Checked on real documents from public datasets

`tools/realworld/realworld.py` takes public invoice and receipt datasets that come with the values printed on them. The datasets are not in this repository because of their licences; the script header says where to clone them. The script:
- makes each document a bad photo: crooked, curled, blurred, dark, and all of these at once;
- reads it with OCR;
- writes the result in the app's report format.

`RealWorldBenchTest` (`REALWORLD_DIR=…`) then runs the app's parser on every reading and scores each field: right and sure, right but checked, wrong but flagged, **wrong and silent**, or missing.

Measured on 1,956 readings of 326 documents: invoice2data (real invoices in EN/FR/NL/DE/PL), SROIE (scanned receipts), invoice_dataset (French invoices) and Eoxia (French receipt photos). Tesseract was the OCR, standing in for the phone's ML Kit. Before → after this round:

| field | right before | right after | wrong and silent after |
|---|---|---|---|
| supplier | 38.3% | 49.1% | 0.9% |
| date | 57.9% | 62.4% | 2.8% (mostly digits misread inside the date) |
| number | 16.0% | 23.5% | 0.5% |
| total | 20.8% | 50.5% | 0.1% |
| taxable amount | 0% | 49.5% | 0.3% |
| VAT | 0% | 49.3% | 0% |
| lines found | 16.9% | 42.9% | |

No document would have been saved without review while wrong.

What changed, for every supplier and language:
- **Words:** totals, taxable amount, VAT, numbers, dates, customer blocks and company forms in English, French, German, Spanish, Dutch and Polish.
- **Dates:** month names in those languages, "September 8, 2022", "08-Sep-22", and month-first order only when no other reading exists.
- **Numbers and units:** thousands written with a space ("12 160,00", joined on a line only when quantity × price proves it), and units in other languages.
- **A total is sure only when something independent confirms it:** taxable + VAT, the lines, the VAT summary, the payment, or a second total line. A label alone ("TOTAL") is not enough. The same holds for the taxable amount and VAT, and for a total worked out as taxable + VAT.
- **Dates on the phone:** a date in the future or over two years old is shown for checking.

The French receipt photos are not readable by Tesseract, so they can only be judged with the phone's OCR.

## 7b-5. Documents corrected by hand (for fixing mistakes in batches)

Each time the operator changes a value before saving, or edits a saved document, the app keeps the list of changes and the full reading report. They are stored on the phone only, up to 300 documents. Settings ▸ *Documents corrected by hand* ▸ **Send the corrected documents** puts them all in one text file for the share sheet, together with the operation log. After a week of normal use, every mistake can then be fixed at once from real evidence.

## 7b-5b. A replacement under every doubtful value

Under every value the app could not settle (orange) or could not find, the review screen offers the best other readings it has; one tap takes one (core `Replacements`). Nothing is applied by itself. Where they come from, most trusted first:

1. the document's own arithmetic from values that are sure: quantity × price = amount (and the quantity or the price from the other two), taxable + VAT = total, the lines' sum;
2. what the AI read where it differs from the reading, even when it could not be proven (for example the supplier's name spelled "ABC" where the OCR read "ABG"; such a difference now also marks the name for a look);
3. suppliers already saved on the phone, or in the knowledge pack, whose name is the misread one give or take a letter;
4. for a date in the future or years ago, the same day and month in a plausible year;
5. for a line's VAT rate, the rate every other line has;
6. the arithmetic from values that are themselves doubtful.

## 7b-6. Saved documents read again after every update

Each saved document keeps its OCR reading (text with positions) on the phone. After an update, the new version reads every saved document again and compares with what the operator saved: date, number, total, taxable, VAT and each line amount. Settings ▸ *Saved documents read again* shows how many values were read as saved, and which documents the new version reads **worse** than the version before; those are also kept with the documents corrected by hand, so they reach the next fix. Documents saved before this existed are read again from their recognised text. E-invoices (XML) are skipped: they are not read from a photo.

## 7b-7. Network: information comes in, nothing goes out

Settings ▸ Network has three modes:

| Mode | What connects |
|---|---|
| Offline (default) | Nothing. New knowledge arrives with app updates, or from a pack file the operator opens. |
| Hybrid | Only when the operator taps **Download now**. |
| Automatic | Also about once a week by itself, on Wi-Fi only (checked when the app starts). |

- **Knowledge pack** (`knowledge/pack.json`, see `knowledge/README.md`): one public file, the same for everyone, downloaded whole from this repository. No question about any document is asked. It lists suppliers by VAT number: a supplier never saved on this phone is named from the pack on its first document (sure when the name read agrees, otherwise shown for a glance). The pack is **empty for now**; filling it with public registry data is a later step. A copy is bundled with the app.
- **Look up products online** (separate switch, off by default, only in Hybrid/Automatic): on a product's page, the app shows the exact words it would send (for example `passata pomodoro`) and, on **Search**, asks Open Food Facts. Only product words are sent: never amounts, prices, dates, document or VAT numbers, or company names (a description with a company name is cut before it). Answers only suggest brand and category, applied when the operator taps **Use**.
- Requests carry a neutral app name instead of Android's default, which would name the phone model.

## 7c. Less typing, price changes and inventory

- **Automatic save.** After a scan the app checks:
  - supplier, date and total were found;
  - nothing was read with low confidence;
  - every line has quantity, unit and amount;
  - the lines add up to the printed taxable amount or total (this also settles whether prices include VAT);
  - the document is not a possible duplicate.

  If all of this holds, the document is saved straight away and opens with a green "Saved automatically" note. Otherwise the review screen lists exactly what to check. Turn it off in Settings ▸ *Less typing*.
- **Price changes.** Each line is compared with the previous purchase of the same product:
  - the price per kg or l for weights and volumes, otherwise per printed unit;
  - the line total ÷ quantity, so discounts count;
  - VAT-inclusive and VAT-exclusive prices are never compared;
  - the same supplier's last price is preferred.

  Changes of 1% or more appear on the review screen, on the saved document, on the product screen and in *Inventory ▸ Price changes*.
- **Inventory.** Home ▸ *Inventory* lists everything bought in a week, month, quarter or year (◀ ▶ to move between periods):
  - grouped by category (fruit & veg, meat, fish, cured meats, dairy & eggs, bakery, dry goods, frozen, drinks, cleaning, packaging, other);
  - with the quantity bought (kg and l added together, other units kept apart unless you defined a conversion), the spend (kept apart by VAT basis), the number of purchases, and the **usual amount per period** (average of up to 6 earlier periods).

  Categories are guessed from an Italian keyword dictionary, reading the product name first ("PAT.SACCHI" is potatoes, not bags) and supplier abbreviations ("BISC.", "CIP.", "PARM."); each category has its own icon. Change one on the product screen: a category you chose is never changed by the app, and the app learns from it — the word of the name the guess hinged on now means your category for every product with that word, from any supplier (phone only; cleared with *Learned from you*).

## 7c-2. Reading in the background

1. Add every page first: take photos, or pick several from the gallery (several pages of one document).
2. Tap **Read these N pages**. Reading starts only now, once all pages are in.

Then:
- The app goes straight back to the home screen.
- The document shows there with its progress ("Reading page 2 of 3", "AI reader…"). A notification shows the same, so you can use other screens, scan the next document, or leave the app.
- Documents are read one after another.
- When one is done, a notification says **Ready to check** (tap to open it), or **Document saved** if everything checked out and it was saved automatically.

Background reading also survives the app being closed:
- A finished reading comes back ready to check, rebuilt from what was stored, without reading again.
- An unfinished one starts again.

The notification needs the Android notification permission (asked once).

## 7d. On-phone AI reader (optional)

A vision AI model (Qwen3-VL, Apache 2.0) reads the photo together with the normal OCR text. It runs **entirely on the phone**, through llama.cpp compiled into the app (CPU, the fastest variant for the phone is picked at start). The app never downloads the model itself: it is downloaded once by the phone's browser and then loaded with the file picker.

**Setup:** Settings ▸ AI reader:
1. Choose the model: 2B (recommended, about 1.5 GB) or 4B (about 3 GB).
2. Tap the two download buttons; each opens huggingface.co in the browser.
3. When both downloads have finished, tap *Load the 2 downloaded files* and select both.
4. Tap *Test*.

**Speed-ups built in:**
- It sees only the part of the photo with text (the table around the paper and the empty paper are cut away).
- The photo is scaled so the print is about 22 pixels tall.
- It uses all fast cores to read the image and the performance cores to write.
- On documents of several pages it reads only the pages that need it: a line that does not add up, the first page if the supplier or date is missing, the last page if the total is missing.
- Measured in CI (4-core cloud computer, one page): the cut-down photo took 2B from 418 s to 330 s and 4B from 855 s to 654 s, still with every line right.

**Small questions first (default):** the AI is not asked to re-read whole pages. It gets a small picture of only what the regular reading could not prove, and answers in a few lines:
- the column headings plus one line where quantity × price does not give the amount (or a line with an amount that was not read as a product);
- the top of the first page, if the supplier or date is missing;
- the bottom of the last page, if the total is missing.

A line is replaced only when the AI's version adds up and its digits were seen by the OCR. Column headings the layout reader does not recognise are found by their words; a page with no headings at all still gets small questions for the lines found on it. Whole pages are read only as a last resort, when the regular reading failed broadly (most lines wrong or not found on the page), or when *Always* is chosen. The second OCR pass (enhanced image) is skipped when the first reading already adds up.

**No confirmation for proven numbers:** when every line has quantity × price = amount, the lines add up to the printed taxable amount or total, and taxable amount + VAT = total, those numbers are not highlighted for checking.

**When it runs:**
- *When needed* (default) runs it only if the normal reading does not add up.
- *Always* runs it on every document.
- Expect about 2–5 minutes per page on a fast phone; the screen stays on meanwhile.

**Checks on the AI's answer:**
- The answer is forced into a fixed JSON shape (a GBNF grammar).
- Every amount must appear in the OCR text or be proven by quantity × price.
- A lot is kept only if it is printed and is not a date.
- Your own business is never taken as the supplier.
- The AI's lines replace the normal reading only when more of them add up.

**Measured in CI** (job `ai-model-check`): the app's own reader code, prompt and grammar, run with the real models on a synthetic invoice photo.
- With the OCR text, both 2B and 4B got all 12 lines right: amounts, quantity, price, header and totals.
- Without the OCR text, 2B got 11 of 12, which is why both are used together.
- Time on a 4-core cloud computer: 2B about 7 min per page, 4B about 13 min. A recent phone with 8 fast cores should be faster, but this has not been measured on a real phone.

**AI instruction language** (measured in CI, same synthetic documents): whole-page reading was equally right with English and Italian instructions (2B: 12/12 both), and took the same time. On single-line questions 2B did worse in Italian (0/2 vs 1/2), 4B was right in both (2/2). The one-letter question was right in both languages on both models. The instructions stay in English; both are kept in the code (`AiReader.Lang`) and measured on every CI run.

**Build:** `third_party/llama.cpp` is cloned automatically (pinned tag `b11242`, Gradle task `:app:fetchLlamaCpp`). This needs `git`, and the NDK/CMake from Android Studio's SDK Manager.

## 7e. Report for the owner

Reports ▸ *Report for the owner*, or the *Send report* tile on the home screen:
1. Pick week, month or quarter, and Italian or English.
2. Tap **Create and send**.

The app then:
- builds an A4 PDF on the phone with total spend compared with the period before, spend by supplier (with bars), spend by category (VAT-inclusive and VAT-exclusive amounts never added together), price changes, and what was bought compared with the usual amount;
- opens the share sheet (WhatsApp, Mail…) with a one-line summary as the message.

The PDF opens natively on an iPhone. Nothing leaves the phone until you pick where to send it.

## 7f. E-invoices (FatturaPA)

Most suppliers also send the invoice as an e-invoice: the XML file that goes through the SdI. Reading that file is exact, so no photo, OCR or AI is needed. The app takes it in any of the usual forms:
- the plain `.xml`, the signed `.xml.p7m` (DER, chunked BER, or base64), or a `.zip` with many invoices. SdI receipts inside a zip are skipped, and a file with several invoices is split into one document each;
- from the **Import a PDF or e-invoice** button, or with *Open with* / *Share to* Kitchen Receipts from Mail, Files, Drive or WhatsApp. Photos and PDFs can be shared to the app the same way.

What is read: the supplier and its VAT number, number, date, every line (the supplier's article code, description, quantity, unit, the price actually paid after line discounts, amount, VAT rate, lot and expiry date), the VAT summary and the total. Values come from the standard's element names, so any supplier's software works.

The usual rules still apply:
- A value the XML does not contain stays empty. For example, a transport charge without a quantity goes to review and nothing is invented.
- Reference-only lines ("Rif. DDT … del …", zero amount) are not items.
- An invoice addressed to another VAT number is held for review.
- Credit notes (TD04, TD08) are money back, not purchases, so they are not imported. The app says so instead of adding stock.
- The document page shows the invoice laid out as text, and the original XML can be opened from the document.

## 7g. Office copy for a computer (the owner's Mac)

Reports ▸ *Office copy for a computer*: choose a password (at least 8 characters), then tap **Create and share**. The phone makes one `.html` file with everything recorded: suppliers, products, documents with every line, prices and lots. Photos and PDFs are not included. Send it by Mail, Quick Share, Drive, WhatsApp or similar.

On the Mac, double-click the file. It opens in Safari (or any modern browser) and asks for the password. It then shows:
- **Overview:** spend for the period against the previous one, spend per month, by category (amounts with and without VAT are never added together), top suppliers, and the biggest price increases.
- **Suppliers, Products** (quantities, spend, last price, min–max, price history chart), **Price changes** (same unit and same VAT basis only), **Documents** (every line), **Lots** (search a lot for traceability).
- Any period, Italian or English, sortable tables, CSV export (Excel-ready), and printing.
- E-invoice files (.xml, .p7m, .zip) dropped on the page join the data for that session. The page also works without an office copy as a plain e-invoice viewer.

How the data is protected:
- The data is compressed and encrypted with AES-256-GCM, using a key derived from the password with PBKDF2-SHA256 (600,000 rounds). The browser's built-in Web Crypto opens it.
- The password is never stored. Without it the file cannot be read, so give it in person or by phone, not in the same message as the file.
- The page's security policy forbids every network connection. It runs offline, loads nothing from the internet and sends nothing. Closing the tab forgets the data, and nothing typed on the page is saved.
- The copy is read-only. Corrections are made on the phone, and a new copy is shared when needed.

CI opens a sample copy (invented data) in Chrome with the network cut off. It checks a wrong and the right password, every tab, the detail windows, CSV export, dropping an e-invoice (including a duplicate), English, dark mode and phone width, and that no connection was attempted. Screenshots go to the `ci-office` branch.

## 7h. Backup and restore

Settings ▸ *Backup* (a reminder appears on the home screen when there is data and no backup for a week):
- **Make backup**: choose a password (at least 8 characters), then where to save the file (Drive, a folder, a USB stick). One `.krbackup` file holds the database, every original photo and PDF, the saved OCR readings, what the app learned, the documents corrected by hand and the settings. Not included: the AI model and the knowledge pack (downloaded again) and the log.
- **Restore**: pick the file and type its password. The whole file is decrypted and checked into a staging folder first (password, every chunk, SQLite `integrity_check`, database not newer than the app); only then does the app ask to replace everything. What was on the phone is moved aside and put back if anything fails half-way; it is deleted only after the restored data has opened once. The app restarts by itself.

How the file is protected (`core/Backup.kt`): a zip stream encrypted with AES-256-GCM in 1 MB chunks (key from the password with PBKDF2-SHA256, 600,000 rounds). Each chunk's nonce carries its number and a "last chunk" flag, so a chunk that is changed, moved or dropped, or a file cut short, is refused. Memory use stays small whatever the size. The database is copied inside a write transaction (main file plus its write-ahead log), so the copy is consistent. Nothing is sent anywhere by the app: the file goes only where the operator saves it.

Tests: `BackupTest` (core: round trip, wrong password, not a backup, cut at many points, a changed byte, a dropped chunk, unsafe names) and `BackupRoundTripTest` (on the emulator: a real backup of the app's data, then the full check of it).

## 8. MVP assumptions and limits

- Main target: Italian supplier documents in EUR. Other currencies can be typed as a 3-letter code, but no conversion is done.
- A seller name is required to save a document, because everything is organised by seller. All other fields are optional and simply stay missing.
- The review draft lives in memory. If Android kills the app while you are on the review screen, the draft is lost and you need to scan again; the orphaned file is cleaned up after an hour.
- Up to 20 pages per document are OCR'd. All pages are always stored and viewable.
- Files over 50 MB are rejected. Supported formats are JPEG, PNG, PDF and e-invoices (.xml, .p7m, .zip). Password-protected PDFs cannot be opened.
- The signature of a .p7m is not verified (the SdI verified it before delivering the file); only the invoice inside is read.
- Product averages are recomputed in memory from all purchases, which is fine for thousands of line items. A very large history would need pre-aggregated tables.
- No cloud sync or live multi-user access. Sharing with the office is a file made on request (see 7g), not a live connection.

## 9. What was verified

Built and tested in an environment **without** access to Google's Maven repository or the Android SDK.

| Check | Status |
|---|---|
| `:core` compiles (Kotlin 2.0.21) | ✅ verified |
| `:core` unit tests: 164 tests on synthetic Italian fixtures (invoices, receipts, delivery notes, messy phone OCR) | ✅ all passing |
| All 34 Room `@Query` statements and the v1→v2 migration SQL run in SQLite against a schema matching the entities | ✅ verified |
| String resources: every referenced key exists in English and Italian, with matching format arguments | ✅ verified |
| `:app` Android build (Room/KSP code generation, Compose compilation, APK) | ✅ built by GitHub Actions on every push |
| Instrumented tests (`MigrationTest`, `RepositoryTest`, `OcrEndToEndTest`) | ✅ run on an Android 11 emulator in GitHub Actions |
| On-device OCR quality on real photos | ⚠️ not measured |

If the first Android build reports a compile error, it will most likely be a small API mismatch in the Compose UI code. That code was written against the pinned library versions but could not be compiled here.

## 10. Project layout

```
core/src/main/kotlin/com/kitchenreceipts/core/
  ItalianNumbers.kt    exact amount parsing/formatting (cents, BigDecimal)
  ItalianDates.kt      Italian date formats
  LotExtractor.kt      lot vs expiry
  Units.kt             unit aliases, mass/volume conversions
  ReceiptParser.kt     OCR text -> ParsedDocument with confidence per field
  LayoutRows.kt        rebuild table rows from OCR bounding boxes
  Drafts.kt            editable review model + validation
  CostCalculator.kt    weighted averages by unit family and VAT basis
  DuplicateDetector.kt duplicate hints, seller/number normalisation
  ProductMatching.kt   alias keys and suggestions (no auto-merge)
  Reports.kt           monthly by seller + CSV
core/src/test/...      JUnit tests + resources/fixtures (synthetic documents)

app/src/main/java/com/kitchenreceipts/app/
  KitchenReceiptsApp.kt  dependency container (swap OCR engine here)
  data/                  Room entities, DAOs, database + migrations, repository
  files/                 FileStore (private originals, SHA-256), PageRenderer (photos/PDF pages)
  ocr/                   OcrEngine interface, ML Kit engine, import pipeline
  ui/                    Compose screens: home, capture, review, documents, products, sellers, reports, viewer
app/src/androidTest/     MigrationTest, RepositoryTest
```

All fixture data is invented (see `core/src/test/resources/fixtures/README.md`). It does not describe any real business or customer.
