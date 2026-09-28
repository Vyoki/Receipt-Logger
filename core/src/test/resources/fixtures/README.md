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
