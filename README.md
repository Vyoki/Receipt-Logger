# Kitchen Receipts

A native Android app (Kotlin + Jetpack Compose) for managing restaurant supplier receipts and invoices.

You photograph a receipt or invoice, or import an image or PDF. The app reads the text **on the phone**, and you check and correct the values it found. Purchases are then organised by seller and month, with prices, lot numbers and weighted average costs per product.

Everything is stored locally in a Room database, so the app works fully offline. It needs no account, no API key and no runtime permissions.

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
| OCR | On-device ML Kit model bundled in the APK | None. The app does not even request `INTERNET`. |
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

**Privacy and security: what is on this phone stays on this phone.**
- **No network at all**: the manifest removes `INTERNET` and network-state permissions (including any that libraries try to add), so Android itself blocks every connection from the app.
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

  Categories are guessed from an Italian keyword dictionary; change one on the product screen.

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

A vision AI model (Qwen3-VL, Apache 2.0) reads the photo together with the normal OCR text. It runs **entirely on the phone**, through llama.cpp compiled into the app (CPU, the fastest variant for the phone is picked at start). The app has **no internet permission**: the model is downloaded once by the phone's browser and then loaded with the file picker.

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

**Build:** `third_party/llama.cpp` is cloned automatically (pinned tag `b11242`, Gradle task `:app:fetchLlamaCpp`). This needs `git`, and the NDK/CMake from Android Studio's SDK Manager.

## 7e. Report for the owner

Reports ▸ *Report for the owner*, or the *Send report* tile on the home screen:
1. Pick week, month or quarter, and Italian or English.
2. Tap **Create and send**.

The app then:
- builds an A4 PDF on the phone with total spend compared with the period before, spend by supplier (with bars), spend by category (VAT-inclusive and VAT-exclusive amounts never added together), price changes, and what was bought compared with the usual amount;
- opens the share sheet (WhatsApp, Mail…) with a one-line summary as the message.

The PDF opens natively on an iPhone. Nothing leaves the phone until you pick where to send it.

## 8. MVP assumptions and limits

- Main target: Italian supplier documents in EUR. Other currencies can be typed as a 3-letter code, but no conversion is done.
- A seller name is required to save a document, because everything is organised by seller. All other fields are optional and simply stay missing.
- The review draft lives in memory. If Android kills the app while you are on the review screen, the draft is lost and you need to scan again; the orphaned file is cleaned up after an hour.
- Up to 20 pages per document are OCR'd. All pages are always stored and viewable.
- Files over 50 MB are rejected. Supported formats are JPEG, PNG and PDF; password-protected PDFs cannot be opened.
- Product averages are recomputed in memory from all purchases, which is fine for thousands of line items. A very large history would need pre-aggregated tables.
- No cloud sync or multi-user support.

## 9. What was verified

Built and tested in an environment **without** access to Google's Maven repository or the Android SDK.

| Check | Status |
|---|---|
| `:core` compiles (Kotlin 2.0.21) | ✅ verified |
| `:core` unit tests: 159 tests on synthetic Italian fixtures (invoices, receipts, delivery notes, messy phone OCR) | ✅ all passing |
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
