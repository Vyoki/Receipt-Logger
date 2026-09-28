# Synthetic test fixtures

All sellers, customers, VAT numbers (P.IVA), addresses and amounts in these files are
**invented** for testing. They do not describe any real business or customer.
They imitate the OCR text of typical Italian supplier documents:

| File | Kind | What it exercises |
|---|---|---|
| fattura_caseificio.txt | Invoice, VAT-exclusive | table header, lots on following lines, expiry next to lot, "Lotto <date>" rejected, weighted qty (1,235 kg) |
| scontrino_mercato.txt | Retail receipt (documento commerciale), VAT-inclusive | "2 x 1,20", "1L", missing quantity, payment/change lines ignored, 2-digit year |
| ddt_ortofrutta.txt | Delivery note (DDT) | textual date "3 aprile 2025", customer block skipped, "Scadenza" never used as lot, deliberate line-total mismatch |
| fattura_macelleria.txt | Invoice, two VAT rates | thousands separators "1.420,70", customer printed before seller, VAT sum across rates, no table header |
| scontrino_ocr_reale.txt | Retail receipt as a phone camera reads it | "2,5O" (letter O), "3 ,90", VAT letter "B" after a price, quantity "2 x 1,25" on its own line, description and amounts on separate lines, "l4-03-2025" |
| fattura_colonne.txt | Invoice with a two-column header | seller and customer on one row, "Numero / Data" labels above their values, "Imponibile" as a column header, discount column, a description split over two rows, total labels with the amount on the next line |
| fattura_cash_and_carry.txt | Cash & carry invoice, page 1 of 2 | VAT code column after every amount, item code + colli before the description, packaging code and pack size before the quantity ("SK GR 800 1 3,450 3,45 10"), weighed items ("NC KG 4,45 …"), number/date under column headings, customer's own VAT number printed, "SEGUE >>>" (no totals on this page) |
| fattura_cash_and_carry_5_pagine.txt | The same invoice, all 5 pages (pages separated by form feed) | a genuine repeated line across the page break, "TOTALE IMPONIBILI" (plural), VAT summary by rate, payment lines, a loyalty-points page with "TOTALE IMPONIBILE SCONTO € 13,23" that must not replace the real totals, a card-payment slip page |
| ocr_mlkit_cash_and_carry.txt | Rows exactly as ML Kit read a rendered copy of the cash & carry page on the Android emulator | real OCR quirks: missing small numbers (quantity "1", VAT code), "D0CUMENTO" with a zero, "0" for the offer marker "O", item code glued to colli ("10000032x3") |
| ddt_surgelati.txt | Delivery note (DDT) of a frozen/fresh food wholesaler | seller and "DESTINATARIO" on one row, number "B26 111945" and date under "NUMERO / DATA" headings next to payment terms, conservation letter between quantity and price ("40,000 C 2,384"), lot numbers on the row under each item ("ID LOTTO" column), category rows ("Merce non deperibile - Congelato"), VAT summary table, "TOTALE DOCUMENTO" on the same row as a VAT line, "TOTALI 209,76 21,51" |

- `ocr_mlkit_lines_4_photos.txt`: the raw line boxes ML Kit returned on an Android emulator for the invented
  "ABC S.r.l." cash & carry page, photographed four ways (straight, angled, angled + enhanced image,
  angled + rotated 3°). Format per case: `# name`, then `left,top,right,bottom,angle | text`.
