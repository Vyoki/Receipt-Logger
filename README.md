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

**Instrumented tests** (Room migration 1→2 and the repository; they run on a phone or emulator):

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

- Products are **never merged automatically**:
  - The picker only *suggests* similar names; you tap to assign, or create a new product.
  - When you assign a description, the app remembers it for **that seller and exactly that description** (case, accents and spacing ignored), and pre-fills it next time. You can remove remembered descriptions on the product screen.
- **Duplicate warning** before saving. It triggers on:
  - the same file bytes (SHA-256);
  - the same seller and document number (`FT 0145/2025` = `ft-145-2025`);
  - the same seller, date and total;
  - or, as a weaker hint, the same date and total from a different seller.

  You can still save, for example for two genuinely identical deliveries.
- Seller names are matched ignoring case, punctuation and legal form (`Caseificio Valverde S.r.l.` = `CASEIFICIO VALVERDE SRL`), so one seller is not split into several.

### Database and migrations

- Room database `kitchen_receipts.db`, currently **schema version 2**:
  - v1 had sellers, documents, products, line_items and product_aliases.
  - v2 added `unit_conversions` (`Migrations.MIGRATION_1_2`).
- There is no destructive fallback: a missing migration fails loudly in testing instead of wiping data.
- `MigrationTest` builds a real v1 file with data, migrates it, and lets Room validate the resulting schema against the entities.
- Room exports schema JSON to `app/schemas/` on build. Commit that folder, and add a migration plus a test for every future schema change.
- Every line item has a non-null `document_id` foreign key to its source document (`ON DELETE CASCADE`).
- Originals are copied into app-private storage (`files/documents/`), so a saved record always shows its original, even if the source file is deleted from the phone.
- Android Auto Backup is enabled. It copies at most 25 MB per app to the user's Google account, so large document collections should also be exported.

---

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
| `:core` unit tests: 68 tests, including 6 synthetic Italian fixtures (2 imitate messy phone OCR) | ✅ all passing |
| All 34 Room `@Query` statements and the v1→v2 migration SQL run in SQLite against a schema matching the entities | ✅ verified |
| String resources: every referenced key exists in English and Italian, with matching format arguments | ✅ verified |
| `:app` Android build (Room/KSP code generation, Compose compilation, APK) | ⚠️ **not run**: no Android SDK was available. Check with `./gradlew :app:assembleDebug`. |
| Instrumented tests (`MigrationTest`, `RepositoryTest`) | ⚠️ **not run**: they need a device or emulator |
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
