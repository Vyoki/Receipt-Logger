# Knowledge pack

`pack.json` is public knowledge the app can take **into** the phone. Nothing ever goes out: the app downloads this
one file whole (the same file for everyone), opens it from a file, or uses the copy bundled with the app
(`app/src/main/assets/knowledge-pack.json`), depending on Settings › Network.

Format 1:

```json
{
  "format": 1,
  "version": "yyyy-MM-dd",
  "suppliers": [ { "country": "IT", "vat": "01234567897", "name": "ABC S.r.l." } ]
}
```

- `version`: a newer date replaces the pack on the phone.
- `suppliers`: companies by VAT number. A document from a supplier never saved on the phone is named from here.
  Italian numbers must pass the Partita IVA checksum, or the entry is ignored.

Only public company registry data belongs here — never a customer's documents or the restaurant's own data.
The pack is empty for now; it will be filled with public registry data in a later step.
