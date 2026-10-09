#!/usr/bin/env python3
"""
Invents food & beverage supplier documents for training the app's AI reader, in Italian and English:
invoices, delivery notes (DDT), deferred invoices printed from the e-invoice, cash & carry receipts, and
handwritten delivery notes. Everything is invented (names, VAT numbers with a valid checksum, products, prices);
no real document is used.

For each document it writes, in OUT/<id>/:
  page.jpg     the page as a phone photo (tilt, perspective, shadow, blur, noise, JPEG)
  reading.txt  what the phone's OCR would return, in the app's "Raw lines" report format, with its slips
               ("0" for "O", a lost decimal comma, a table rule glued to a number) and its confidence per word
  truth.json   what is really printed: the right answer to every question the app can ask

  python3 tools/training/gen_docs.py OUT --count 2000 --seed 1 [--fonts DIR_WITH_HANDWRITING_TTFS]

Deterministic for a seed. Needs Pillow, numpy, opencv-python.
"""
import argparse
import json
import math
import os
import random
import sys
from dataclasses import dataclass, field

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

OWN_VAT = "09876543217"           # the restaurant (the customer on every document): invented
OWN_NAME = ["RISTORANTE PROVA SAS", "TRATTORIA ESEMPIO S.R.L.", "OSTERIA DEL TEST SNC"]

PRINT_FONTS = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    "/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf",
    "/usr/share/fonts/truetype/freefont/FreeMono.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
]
BOLD = {
    "LiberationSans-Regular": "LiberationSans-Bold", "LiberationMono-Regular": "LiberationMono-Bold",
    "LiberationSerif-Regular": "LiberationSerif-Bold", "DejaVuSans": "DejaVuSans-Bold",
    "DejaVuSansCondensed": "DejaVuSansCondensed-Bold", "DejaVuSansMono": "DejaVuSansMono-Bold",
    "Carlito-Regular": "Carlito-Bold", "FreeMono": "FreeMonoBold", "FreeSans": "FreeSansBold",
}
FALLBACK_HAND = ["/usr/share/texmf/fonts/opentype/public/tex-gyre/texgyrechorus-mediumitalic.otf"]

# ---------------------------------------------------------------------------------------------- vocabulary

# (italian, english, category, weighed, vat, price range per kg / piece)
PRODUCTS = [
    ("PETTO DI POLLO", "CHICKEN BREAST", "carni", True, 10, (6, 10)),
    ("COSCE DI POLLO", "CHICKEN THIGHS", "carni", True, 10, (4, 7)),
    ("CONTROFILETTO BOVINO", "BEEF SIRLOIN", "carni", True, 10, (22, 38)),
    ("FILETTO DI MANZO", "BEEF FILLET", "carni", True, 10, (35, 55)),
    ("MACINATO SCELTO", "MINCED BEEF", "carni", True, 10, (8, 13)),
    ("SALSICCIA DI MAIALE", "PORK SAUSAGE", "carni", True, 10, (6, 10)),
    ("COSTINE DI MAIALE", "PORK RIBS", "carni", True, 10, (5, 9)),
    ("AGNELLO COSCIA", "LAMB LEG", "carni", True, 10, (11, 18)),
    ("GUANCIALE STAGIONATO", "CURED PORK CHEEK", "salumi", True, 10, (11, 18)),
    ("PROSCIUTTO CRUDO STAG.", "CURED HAM", "salumi", True, 10, (14, 28)),
    ("MORTADELLA IGP", "MORTADELLA", "salumi", True, 10, (8, 14)),
    ("SALMONE FILETTO FRESCO", "FRESH SALMON FILLET", "pesce", True, 10, (16, 26)),
    ("BRANZINO 400/600", "SEA BASS 400/600", "pesce", True, 10, (12, 20)),
    ("GAMBERI ARGENTINA L1", "ARGENTINE PRAWNS L1", "pesce", True, 10, (14, 24)),
    ("COZZE NOSTRANE", "MUSSELS", "pesce", True, 10, (2.5, 5)),
    ("POLPO DECONGELATO", "OCTOPUS THAWED", "pesce", True, 10, (12, 19)),
    ("MOZZARELLA FIOR DI LATTE 125G", "MOZZARELLA 125G", "latticini", False, 4, (0.6, 1.3)),
    ("BURRATA 250G", "BURRATA 250G", "latticini", False, 4, (2.5, 4.5)),
    ("PARMIGIANO REGGIANO 24 MESI", "PARMESAN 24 MONTHS", "latticini", True, 4, (14, 22)),
    ("GRANA PADANO DOP", "GRANA PADANO PDO", "latticini", True, 4, (9, 15)),
    ("PECORINO ROMANO DOP", "PECORINO ROMANO PDO", "latticini", True, 4, (12, 19)),
    ("RICOTTA VACCINA KG 1,5", "COW RICOTTA 1.5KG", "latticini", False, 4, (4, 7)),
    ("PANNA DA CUCINA LT 1", "COOKING CREAM 1L", "latticini", False, 4, (2.5, 4.5)),
    ("BURRO 1KG", "BUTTER 1KG", "latticini", False, 4, (7, 11)),
    ("UOVA FRESCHE CAT. A X30", "FRESH EGGS X30", "uova", False, 4, (4.5, 8)),
    ("POMODORI PELATI KG 2,5", "PEELED TOMATOES 2.5KG", "secco", False, 4, (2, 4)),
    ("PASSATA DI POMODORO 700G", "TOMATO PASSATA 700G", "secco", False, 4, (0.8, 1.6)),
    ("FARINA 00 KG 25", "FLOUR 00 25KG", "secco", False, 4, (14, 24)),
    ("SPAGHETTI N.5 KG 3", "SPAGHETTI 3KG", "secco", False, 4, (3.5, 7)),
    ("RISO CARNAROLI KG 1", "CARNAROLI RICE 1KG", "secco", False, 4, (2.5, 5)),
    ("OLIO EVO LT 5", "EXTRA VIRGIN OIL 5L", "secco", False, 4, (35, 60)),
    ("OLIO DI SEMI LT 10", "SEED OIL 10L", "secco", False, 22, (18, 30)),
    ("ZUCCHERO KG 1", "SUGAR 1KG", "secco", False, 10, (0.9, 1.6)),
    ("SALE GROSSO KG 1", "COARSE SALT 1KG", "secco", False, 22, (0.3, 0.8)),
    ("CAFFE IN GRANI KG 1", "COFFEE BEANS 1KG", "bevande", False, 22, (12, 24)),
    ("ACQUA NATURALE 75CL X12", "STILL WATER 75CL X12", "bevande", False, 22, (4, 9)),
    ("ACQUA FRIZZANTE 75CL X12", "SPARKLING WATER 75CL X12", "bevande", False, 22, (4, 9)),
    ("BIRRA BIONDA 33CL X24", "LAGER 33CL X24", "bevande", False, 22, (18, 32)),
    ("VINO ROSSO DOC 75CL", "RED WINE DOC 75CL", "bevande", False, 22, (4, 14)),
    ("PROSECCO DOC EXTRA DRY", "PROSECCO DOC EXTRA DRY", "bevande", False, 22, (5, 12)),
    ("COCA COLA 33CL X24", "COLA 33CL X24", "bevande", False, 22, (12, 20)),
    ("SUCCO ARANCIA LT 1", "ORANGE JUICE 1L", "bevande", False, 22, (1.2, 2.5)),
    ("PATATE", "POTATOES", "ortofrutta", True, 4, (0.7, 1.6)),
    ("CIPOLLE DORATE", "YELLOW ONIONS", "ortofrutta", True, 4, (0.9, 1.7)),
    ("POMODORI CILIEGINO", "CHERRY TOMATOES", "ortofrutta", True, 4, (2, 4.5)),
    ("ZUCCHINE", "COURGETTES", "ortofrutta", True, 4, (1.2, 3)),
    ("MELANZANE", "AUBERGINES", "ortofrutta", True, 4, (1.2, 2.8)),
    ("INSALATA GENTILE", "LETTUCE", "ortofrutta", True, 4, (1.5, 3)),
    ("LIMONI", "LEMONS", "ortofrutta", True, 4, (1.5, 3)),
    ("BASILICO MAZZO", "BASIL BUNCH", "ortofrutta", False, 4, (0.6, 1.5)),
    ("FUNGHI PORCINI SURG.", "FROZEN PORCINI", "surgelati", True, 10, (14, 26)),
    ("PISELLI FINI SURG. KG 2,5", "FROZEN PEAS 2.5KG", "surgelati", False, 10, (5, 9)),
    ("PATATINE PREFRITTE KG 2,5", "FROZEN FRIES 2.5KG", "surgelati", False, 10, (4, 7)),
    ("GELATO VANIGLIA LT 5", "VANILLA ICE CREAM 5L", "surgelati", False, 10, (15, 28)),
    ("DETERSIVO PIATTI LT 5", "DISHWASHING LIQUID 5L", "pulizia", False, 22, (6, 13)),
    ("SGRASSATORE LT 1", "DEGREASER 1L", "pulizia", False, 22, (2, 5)),
    ("TOVAGLIOLI 33X33 X100", "NAPKINS 33X33 X100", "monouso", False, 22, (1.5, 3.5)),
    ("PELLICOLA 30CM X 300M", "CLING FILM 30CM X 300M", "monouso", False, 22, (6, 12)),
    ("CARTA FORNO 40CM X 50M", "BAKING PAPER 40CM X 50M", "monouso", False, 22, (4, 9)),
]

SUPPLIER_WORDS = ["VERDE", "FRESCO", "ALFA", "BETA", "DELTA", "SOLE", "MARE", "MONTE", "VALLE", "FIUME", "AURORA",
                  "CENTRO", "NORD", "SUD", "ORTO", "CAMPO", "PRATO", "LAGO", "COLLE", "STELLA", "GRANO", "OLIVO"]
TRADE_IT = ["INGROSSO", "ALIMENTARI", "DISTRIBUZIONE", "CARNI", "ORTOFRUTTA", "CASEIFICIO", "SURGELATI", "BEVANDE",
            "FORNITURE", "MACELLERIA", "PESCHERIA", "SALUMIFICIO", "FOOD SERVICE"]
TRADE_EN = ["FOODS", "WHOLESALE", "TRADING", "DISTRIBUTION", "PROVISIONS", "SEAFOOD", "MEATS", "BEVERAGES"]
FORMS_IT = ["S.r.l.", "S.R.L.", "SRL", "S.p.A.", "S.P.A.", "S.n.c.", "S.a.s.", "SOC. COOP.", "DI ROSSI MARIO"]
FORMS_EN = ["Ltd", "LTD", "Limited", "GmbH", "B.V.", "S.A.", "Inc."]
TOWNS = ["ROMA (RM)", "MILANO (MI)", "TORINO (TO)", "BOLOGNA (BO)", "FIRENZE (FI)", "NAPOLI (NA)", "BARI (BA)",
         "PADOVA (PD)", "VERONA (VR)", "PARMA (PR)", "GENOVA (GE)", "TRIESTE (TS)"]
STREETS = ["VIA ROMA", "VIA GARIBALDI", "VIALE EUROPA", "VIA DELLE INDUSTRIE", "PIAZZA MAZZINI", "CORSO ITALIA",
           "VIA DEL LAVORO", "VIA DELL'ARTIGIANATO", "STRADA PROVINCIALE"]

# Column headings: every synonym the documents print, Italian and English.
HEADINGS = {
    "it": {
        "code": ["CODICE", "COD.", "COD. ART.", "CODICE ARTICOLO", "ART.", "RIF."],
        "colli": ["COLLI", "N. COLLI", "CL"],
        "desc": ["DESCRIZIONE", "DESCRIZIONE ARTICOLO", "PRODOTTO", "DESCRIZIONE BENI", "DESCRIZIONE MERCE"],
        "unit": ["U.M.", "UM", "UNITA'", "U/M"],
        "qty": ["QUANTITA'", "Q.TA'", "QTA", "QUANT.", "PESO NETTO", "Q.TA"],
        "price": ["PREZZO", "PREZZO UNITARIO", "PR. UNIT.", "P.U.", "PREZZO NETTO", "PREZZO UNIT."],
        "disc": ["SCONTO", "SC.%", "SCONTO O MAGG.", "SC.", "% SC."],
        "amount": ["IMPORTO", "TOTALE", "PREZZO TOTALE", "VALORE", "IMPORTO NETTO", "TOT. RIGA"],
        "vat": ["IVA", "% IVA", "ALIQ.", "C.IVA", "COD. IVA", "%IVA"],
        "lot": ["LOTTO", "N. LOTTO", "LOTTO N."],
        "store": ["*", "CONS.", "T", "CONSERV."],
        "pack": ["TIPO CONF.", "CONF.", "FORMATO", "IMB."],
    },
    "en": {
        "code": ["CODE", "ITEM", "SKU", "ITEM NO.", "REF."],
        "colli": ["PACKS", "CASES", "CTNS"],
        "desc": ["DESCRIPTION", "PRODUCT", "ITEM DESCRIPTION", "GOODS"],
        "unit": ["UOM", "UNIT", "U/M"],
        "qty": ["QTY", "QUANTITY", "NET WEIGHT", "QTY."],
        "price": ["UNIT PRICE", "PRICE", "RATE", "PRICE/UNIT", "UNIT COST"],
        "disc": ["DISC.", "DISCOUNT %", "DISC %"],
        "amount": ["AMOUNT", "TOTAL", "LINE TOTAL", "NET AMOUNT", "VALUE"],
        "vat": ["VAT %", "VAT", "TAX %", "VAT RATE"],
        "lot": ["LOT", "BATCH", "LOT NO."],
        "store": ["*", "STORAGE", "T"],
        "pack": ["PACK", "PACKING", "PACK SIZE"],
    },
}

# Codes a document defines for itself, and the legend it prints to explain them (read from the page, never assumed).
STORAGE = {
    "it": ({"C": "congelato", "S": "surgelato", "F": "fresco", "ST": "stagionato", "CN": "conserva"},
           ["* C congelato / S surgelato / F fresco / ST stagionato / CN conserva", "Legenda: C=congelato S=surgelato F=fresco CN=conserva",
            "(*) C congelato - S surgelato - F fresco - CN conserva"]),
    "en": ({"F": "frozen", "C": "chilled", "A": "ambient"}, ["* F frozen / C chilled / A ambient", "Storage: F=frozen C=chilled A=ambient"]),
}
ROW_TYPES = {
    "it": ({"O": "offerta", "S": "sconto", "C": "cambio prezzo"}, ["TIPO RIGA : O - OFFERTE | S - SCONTO | C - CAMBIO PREZZO",
                                                                    "Tipo riga: O=offerta S=sconto C=cambio prezzo"]),
    "en": ({"P": "promotion", "D": "discount"}, ["LINE TYPE: P - PROMOTION | D - DISCOUNT"]),
}
# A page that goes on, and the next page that carries on from it.
CARRIED = {"it": {"to": ["SEGUE", "SEGUE >>>", "CONTINUA", "SEGUE A PAGINA SUCCESSIVA", "*** SEGUE ***"], "from": ["RIPORTO", "SEGUE DA PAGINA PRECEDENTE", "CONTINUAZIONE"]},
           "en": {"to": ["CONTINUED", "CONTINUED OVERLEAF", "--> NEXT PAGE"], "from": ["CONTINUED FROM PREVIOUS PAGE", "BROUGHT FORWARD"]}}
CONDITIONS = {"it": ["Condizioni generali di vendita", "1) La merce viaggia a rischio e pericolo del committente.",
                     "2) Eventuali reclami devono pervenire entro 8 giorni dal ricevimento.", "3) Non si accettano resi senza autorizzazione.",
                     "4) Per ogni controversia e' competente il foro del venditore.", "Informativa sul trattamento dei dati personali (Reg. UE 2016/679).",
                     "Interessi di mora nella misura prevista dal D.Lgs. 231/2002."],
              "en": ["General terms of sale", "1) Goods travel at the buyer's risk.", "2) Claims within 8 days of delivery.",
                     "3) No returns without authorisation.", "Personal data are processed under Regulation (EU) 2016/679."]}
PACK_TYPES = ["CS", "NC", "BT", "CF", "PT", "SK", "VA", "CT", "FL", "PC", "SQ", "VS"]
GROUPS = {"it": {"surgelati": "Merce non deperibile - Congelato", "carni": "Merce deperibile - Fresco", "latticini": "Merce deperibile - Fresco",
                 "ortofrutta": "Merce deperibile - Fresco", "pulizia": "Merce non alimentare", "monouso": "Merce non alimentare", "_": "Merce non deperibile - Fresco"},
          "en": {"surgelati": "Frozen goods", "pulizia": "Non-food", "_": "Chilled and ambient goods"}}
ORIGINS = ["ITALIA", "SPAGNA", "PAESI BASSI", "FRANCIA", "COSTA RICA", "MAROCCO", "GRECIA"]
UNITS = {"it": {"kg": ["KG", "Kg", "kg"], "pz": ["PZ", "NR", "N.", "CT", "CF", "CONF"]},
         "en": {"kg": ["KG", "kg"], "pz": ["PCS", "EA", "CS", "PK", "UNIT"]}}

LABELS = {
    "it": {
        "invoice": ["FATTURA", "FATTURA ACCOMPAGNATORIA", "FATTURA DIFFERITA"], "ddt": ["DOCUMENTO DI TRASPORTO", "D.D.T.", "DDT"],
        "number": ["N.", "NUMERO", "NR.", "N. DOCUMENTO", "NUMERO DOCUMENTO"], "date": ["DEL", "DATA", "DATA DOCUMENTO", "DATA DOC."],
        "customer": ["Spett.le", "SPETT.LE", "Destinatario", "CLIENTE", "Intestatario"],
        "subtotal": ["TOTALE IMPONIBILE", "IMPONIBILE", "TOT. IMPONIBILE", "TOTALE MERCE"],
        "vat": ["TOTALE IVA", "IVA", "TOT. IVA", "IMPOSTA", "TOTALE IMPOSTA"],
        "total": ["TOTALE DOCUMENTO", "TOTALE FATTURA", "TOTALE DA PAGARE", "NETTO A PAGARE", "TOTALE"],
        "vat_id": ["P.IVA", "Partita IVA", "P. IVA", "C.F. e P.IVA"],
        "discount_line": ["Sconto incondizionato", "Sconto in fattura", "Abbuono", "Sconto cassa 2%"],
        "charge_line": ["Spese di trasporto", "Contributo consegna", "Spese incasso"],
        "supplier_box": ["Cedente/prestatore (fornitore)", "FORNITORE", "Mittente"],
        "customer_box": ["Cessionario/committente (cliente)", "CLIENTE", "Destinatario"],
        "name_label": ["Denominazione:", "Ragione sociale:"],
        "id_label": ["Identificativo fiscale ai fini IVA:", "Partita IVA:", "P.IVA:"],
        "address_label": ["Indirizzo:", "Sede:"],
        "notes": ["Merce viaggia a rischio e pericolo del committente", "Contributo CONAI assolto ove dovuto",
                  "Pagamento: Bonifico bancario 30 gg d.f.", "Conservare a +0/+4 C", "Non si accettano reclami trascorsi 8 giorni"],
        "receipt_total": ["TOTALE COMPLESSIVO", "TOTALE EURO", "TOTALE"],
    },
    "en": {
        "invoice": ["INVOICE", "TAX INVOICE"], "ddt": ["DELIVERY NOTE", "PACKING SLIP"],
        "number": ["Invoice No.", "No.", "Number", "Invoice Number", "Document No."], "date": ["Date", "Invoice Date", "Issue Date"],
        "customer": ["Bill to", "Customer", "Sold to", "Deliver to"],
        "subtotal": ["SUBTOTAL", "NET TOTAL", "TAXABLE AMOUNT", "TOTAL NET"],
        "vat": ["VAT", "TOTAL VAT", "TAX"],
        "total": ["TOTAL", "TOTAL DUE", "AMOUNT DUE", "INVOICE TOTAL", "GRAND TOTAL"],
        "vat_id": ["VAT No.", "VAT ID", "VAT Reg. No."],
        "discount_line": ["Discount", "Trade discount", "Early payment discount"],
        "charge_line": ["Delivery charge", "Transport", "Handling fee"],
        "supplier_box": ["Supplier", "Seller", "From"],
        "customer_box": ["Customer", "Buyer", "To"],
        "name_label": ["Name:", "Company:"],
        "id_label": ["VAT ID:", "VAT No.:"],
        "address_label": ["Address:"],
        "notes": ["Goods remain our property until paid in full", "Payment: bank transfer 30 days",
                  "Keep refrigerated", "Claims within 7 days of delivery"],
        "receipt_total": ["TOTAL", "TOTAL DUE"],
    },
}

# What the OCR typically confuses (applied to the reading, never to the printed truth).
SLIPS = {"0": "O", "O": "0", "1": "l", "l": "1", "I": "1", "5": "S", "S": "5", "8": "B", "B": "8", "6": "G", "2": "Z",
         ",": ".", ".": ",", "E": "F", "rn": "m", "m": "rn"}


# ---------------------------------------------------------------------------------------------- helpers

def vat_number(r):
    d = [0] + [r.randrange(10) for _ in range(9)]
    t = 0
    for i, x in enumerate(d):
        if i % 2 == 0:
            t += x
        else:
            y = x * 2
            t += y - 9 if y > 9 else y
    return "".join(map(str, d)) + str((10 - t % 10) % 10)


def fmt(v, decimals, style):
    """A number as printed: Italian "1.234,56" or English "1,234.56"."""
    s = f"{v:,.{decimals}f}"
    if style == "it":
        s = s.replace(",", "_").replace(".", ",").replace("_", ".")
    return s


def cents(v):
    return int(round(v * 100))


def abbreviate(r, name):
    """Suppliers' abbreviations: "PROSCIUTTO CRUDO STAGIONATO" -> "PROSC. CRUDO STAG."."""
    if r.random() > 0.3:
        return name
    words = name.split(" ")
    out = []
    for w in words:
        if len(w) > 6 and w.isalpha() and r.random() < 0.6:
            out.append(w[: r.randint(3, 5)] + ".")
        else:
            out.append(w)
    return " ".join(out)


@dataclass
class Cell:
    text: str
    x: float
    y: float
    size: int
    font: str
    bold: bool = False
    hand: bool = False
    tag: str = ""        # what it is: "heading:qty", "item:3:amount", "field:total", ...
    align: str = "left"  # left/right: x is the left or the right edge
    page: int = 0


@dataclass
class Doc:
    lang: str
    kind: str
    width: int
    height: int
    cells: list = field(default_factory=list)
    rules: list = field(default_factory=list)   # (page, x1, y1, x2, y2) table lines
    shapes: list = field(default_factory=list)  # (page, "rect" | "ellipse", x1, y1, x2, y2, width): logos and marks
    truth: dict = field(default_factory=dict)
    page: int = 0                               # the page being written


# ---------------------------------------------------------------------------------------------- the document

def make_doc(r, args):
    lang = "it" if r.random() < 0.75 else "en"
    style = "it" if lang == "it" or r.random() < 0.3 else "en"
    kind = r.choices(["invoice", "ddt", "printout", "receipt", "handwritten"], weights=[34, 22, 18, 14, 12])[0]
    if kind == "printout":
        lang, style = "it", "it"
    if kind == "handwritten":
        lang, style = "it", "it"
    L = LABELS[lang]
    seller = " ".join(r.sample(SUPPLIER_WORDS, r.randint(1, 2)) + [r.choice(TRADE_IT if lang == "it" else TRADE_EN),
                                                                  r.choice(FORMS_IT if lang == "it" else FORMS_EN)])
    seller_vat = vat_number(r)
    customer = r.choice(OWN_NAME)
    date = (2026, r.randint(1, 12), r.randint(1, 28))
    date_text = r.choice([f"{date[2]:02d}/{date[1]:02d}/{date[0]}", f"{date[2]:02d}-{date[1]:02d}-{date[0]}",
                          f"{date[2]:02d}/{date[1]:02d}/{date[0] % 100:02d}", f"{date[2]}/{date[1]}/{date[0]}"])
    number = r.choice([f"{r.randint(1, 9999)}", f"{r.randint(10, 99)}{r.choice('ABCDEFGH')}/{r.randint(10000, 99999)}",
                       f"{r.choice('ABFV')}{r.randint(20, 27)} {r.randint(100000, 999999)}", f"2026/{r.randint(1, 9999):04d}",
                       f"FT{r.randint(1000, 99999)}", f"{r.randint(1, 999)}/{r.choice('ABCPE')}"])
    if kind == "receipt":
        return receipt(r, lang, style, seller, seller_vat, number, date, date_text)

    width, height = 1700, 2400
    doc = Doc(lang, kind, width, height)
    font = r.choice(PRINT_FONTS)
    hand = kind == "handwritten"
    hand_font = r.choice(args.hand_fonts) if hand else None
    size = r.randint(22, 28)

    def put(text, x, y, sz=None, bold=False, tag="", align="left", handwritten=False):
        doc.cells.append(Cell(text, x, y, sz or size, hand_font if handwritten else font, bold, handwritten, tag, align, doc.page))
        return doc.cells[-1]

    margin = r.randint(70, 130)
    y = r.randint(70, 140)
    # ---- parties
    supplier_box = None
    if kind == "printout" or (r.random() < 0.15 and kind != "handwritten"):
        # E-invoice printout: the two parties in labelled boxes side by side.
        bx2 = width // 2 + 10
        put(r.choice(L["supplier_box"]), margin, y, bold=True, tag="box:supplier")
        put(r.choice(L["customer_box"]), bx2, y, bold=True, tag="box:customer")
        y += size * 1.6
        idl = r.choice(L["id_label"])
        put(f"{idl} IT{seller_vat}", margin, y, tag="field:seller_vat")
        put(f"{idl} IT{OWN_VAT}", bx2, y)
        y += size * 1.4
        nl = r.choice(L["name_label"])
        if len(seller) > 26 and r.random() < 0.5:
            cut = seller.rfind(" ", 0, 26)
            put(f"{nl} {seller[:cut]}", margin, y, tag="field:seller")
            put(f"{nl} {customer}", bx2, y)
            y += size * 1.3
            put(seller[cut + 1:], margin, y, tag="field:seller")
        else:
            put(f"{nl} {seller}", margin, y, tag="field:seller")
            put(f"{nl} {customer}", bx2, y)
        y += size * 1.4
        al = r.choice(L["address_label"])
        put(f"{al} {r.choice(STREETS)} {r.randint(1, 120)}", margin, y)
        put(f"{al} VIA ESEMPIO 1", bx2, y)
        y += size * 1.4
        put(r.choice(TOWNS), margin, y)
        put("00100 ROMA (RM)", bx2, y)
        supplier_box = True
        y += size * 2.5
    else:
        # Letterhead top left, the customer's block on the right. The name as a logo (block letters in boxes, a
        # word in an oval) above it printed plainly, or only the plain name; a sole trader's name on two lines.
        big = int(size * r.uniform(1.4, 2.0))
        style_logo = r.choice(["plain", "plain", "boxes", "oval"])
        words_ = [w_ for w_ in seller.split() if w_.isalpha() and w_ not in ("DI",)]
        if style_logo == "boxes" and len(words_) >= 2:
            logo = "".join(w_[0] for w_ in words_[:3]).upper()
            bs = int(big * 1.6)
            for k_, ch in enumerate(logo):
                bx = margin + k_ * (bs + 6)
                doc.shapes.append((doc.page, "rect", bx, y, bx + bs, y + bs, 4))
                put(ch, bx + bs * 0.22, y + bs * 0.12, sz=int(bs * 0.8), bold=True, tag="logo")
            y += bs + size * 0.6
        elif style_logo == "oval":
            logo = words_[0] if words_ else seller.split()[0]
            lw = int(len(logo) * big * 0.9)
            doc.shapes.append((doc.page, "ellipse", margin - 20, y - 10, margin + lw + 30, y + big * 2.2, 5))
            put(logo, margin + 8, y + big * 0.35, sz=int(big * 1.3), bold=True, tag="logo")
            y += big * 2.6
        else:
            logo = None
        name_sz = big if logo is None else int(size * 1.1)
        if " DI " in seller and r.random() < 0.6:
            a_, b_ = seller.split(" DI ", 1)
            put(a_, margin, y, sz=name_sz, bold=True, tag="field:seller")
            y += name_sz * 1.2
            put("DI " + b_, margin, y, sz=name_sz, bold=True, tag="field:seller")
        else:
            put(seller, margin, y, sz=name_sz, bold=True, tag="field:seller")
        y2 = y + name_sz * 1.5
        put(f"{r.choice(STREETS)}, {r.randint(1, 120)} - {r.randint(10000, 99999)} {r.choice(TOWNS)}", margin, y2, sz=int(size * 0.85))
        y2 += size * 1.2
        put(f"{r.choice(L['vat_id'])} {seller_vat}", margin, y2, sz=int(size * 0.85), tag="field:seller_vat")
        y2 += size * 1.2
        put(f"Tel. 0{r.randint(10, 99)} {r.randint(100000, 9999999)}", margin, y2, sz=int(size * 0.85))
        cx = int(width * r.uniform(0.52, 0.6))
        cy = y + big * 1.5 + size * 3
        put(r.choice(L["customer"]), cx, cy)
        put(customer, cx, cy + size * 1.3, bold=True)
        put("VIA ESEMPIO, 1", cx, cy + size * 2.6)
        put("00100 ROMA (RM)", cx, cy + size * 3.9)
        put(f"{r.choice(L['vat_id'])} {OWN_VAT}", cx, cy + size * 5.2)
        y = cy + size * 7.5

    if supplier_box:
        logo = None
    # An approval mark: the oval EU stamp at the top right, or the same as text at the foot ("Bollo sanitario").
    approval_mark, approval_text = None, None
    if kind != "handwritten" and r.random() < 0.25:
        code = f"{r.randint(100, 999)} {r.choice('SLMC')}" if r.random() < 0.6 else f"{r.choice('ABCJKN')}{r.randint(100, 999)}{r.choice('ELM')}"
        approval_mark = f"IT {code} CE"
        if r.random() < 0.5:
            ox, oy = int(width * 0.78), 60
            doc.shapes.append((doc.page, "ellipse", ox, oy, ox + 170, oy + 120, 4))
            put("IT", ox + 70, oy + 12, sz=24, bold=True, tag="approval")
            put(code, ox + 35, oy + 44, sz=26, bold=True, tag="approval")
            put("CE", ox + 68, oy + 80, sz=24, bold=True, tag="approval")
        else:
            approval_text = r.choice([f"BOLLO SANITARIO: IT {code} UE", f"Stabilimento riconosciuto IT {code} CE"])

    # ---- document title, number and date
    title = r.choice(L["ddt"] if kind in ("ddt", "handwritten") else L["invoice"])
    if r.random() < 0.5:
        line = f"{title} {r.choice(L['number'])} {number} {r.choice(L['date'])} {date_text}"
        put(line, margin, y, bold=True, tag="field:number_date")
        y += size * 2.4
    else:
        put(title, margin, y, bold=True)
        y += size * 1.8
        # A grid: labels over the values, sometimes over two lines ("Numero / documento").
        gx = [margin + int(width * 0.35), margin + int(width * 0.55)]
        nl, dl = r.choice(L["number"]), r.choice(L["date"])
        two = r.random() < 0.3 and " " in nl
        if two:
            a, b = nl.split(" ", 1)
            put(a, gx[0], y, sz=int(size * 0.8), tag="label:number")
            put(b, gx[0], y + size, sz=int(size * 0.8), tag="label:number")
            put(dl, gx[1], y + size, sz=int(size * 0.8), tag="label:date")
            y += size * 2.1
        else:
            put(nl, gx[0], y, sz=int(size * 0.8), tag="label:number")
            put(dl, gx[1], y, sz=int(size * 0.8), tag="label:date")
            y += size * 1.1
        put(number, gx[0], y, tag="field:number", handwritten=hand)
        put(date_text, gx[1], y, tag="field:date", handwritten=hand)
        y += size * 2.5

    # ---- the item table
    cols = ["desc", "qty", "price", "amount"]
    if r.random() < 0.6: cols.insert(0, "code")
    if r.random() < 0.6: cols.insert(cols.index("qty"), "unit")
    if r.random() < 0.25: cols.insert(cols.index("desc"), "colli")
    if kind in ("ddt", "handwritten") or r.random() < 0.3: cols.insert(cols.index("desc") + 1, "lot")
    # Lots printed on the row under each item (under its code or its description), with a heading row under the
    # description heading ("ID LOTTO") or with no heading at all.
    lot_place = "column"
    if "lot" in cols and not hand and r.random() < 0.5:
        cols.remove("lot")
        lot_place = r.choice(["under_code", "under_desc"]) if "code" in cols else "under_desc"
    lot_heading = lot_place != "column" and r.random() < 0.45
    if kind != "handwritten" and r.random() < 0.25: cols.insert(cols.index("qty") + 1, "store")
    if kind != "handwritten" and r.random() < 0.25: cols.insert(cols.index("qty"), "pack")
    row_types = "code" in cols and kind != "handwritten" and r.random() < 0.2
    if r.random() < 0.3 and kind != "handwritten": cols.insert(cols.index("amount"), "disc")
    if r.random() < 0.6 and kind != "handwritten": cols.append("vat")
    if "unit" in cols and r.random() < 0.25:
        # The unit printed after the price, as some programs do.
        cols.remove("unit"); cols.insert(cols.index("price") + 1, "unit")
    show_prices = not (kind == "ddt" and r.random() < 0.3)
    if not show_prices:
        cols = [c for c in cols if c not in ("price", "disc", "amount", "vat")]
    # The lines first, so each column is as wide as what it holds (as an invoicing program lays it out).
    # Some documents run over several pages (the items continue on the next, the totals on the last page with items or
    # alone on a page of their own, sometimes followed by a page of conditions): each page is photographed on its own.
    multi = kind in ("invoice", "ddt", "printout") and r.random() < 0.18
    n = r.randint(2, 6) if hand else (r.randint(18, 34) if multi else r.randint(2, 14))
    items = []
    unit_words = UNITS[lang]
    price_dec = r.choice([2, 2, 3, 4])
    for i in range(n):
        p = r.choice(PRODUCTS)
        name = abbreviate(r, p[0] if lang == "it" else p[1])
        weighed = p[3]
        qty = round(r.uniform(0.3, 25), r.choice([3, 2, 1])) if weighed else r.randint(1, 24)
        price = round(r.uniform(*p[5]), price_dec)
        disc = None
        if "disc" in cols and r.random() < 0.5:
            disc = r.choice(["10", "5", "20", "30", "10+5", "10+10", "3"])
        keep = 1.0
        if disc:
            for dd in disc.split("+"):
                keep *= 1 - int(dd) / 100
        unit = r.choice(unit_words["kg" if weighed else "pz"])
        qd = 3 if weighed and r.random() < 0.6 else (2 if weighed or r.random() < 0.4 else 0)
        qty = round(qty, qd) if qd else int(round(qty))
        # Rounded half up, to the cent, as invoicing programs do.
        amount = math.floor(qty * price * keep * 100 + 0.5 + 1e-7) / 100
        items.append({
            "kind": "product", "description": name, "code": f"{r.randint(1000, 999999)}" if "code" in cols else None,
            "colli": str(r.randint(1, 6)) if "colli" in cols else None,
            "unit": unit if "unit" in cols else None,
            "quantity": fmt(qty, qd, style), "price": fmt(price, price_dec, style) if show_prices else None,
            "discount": disc, "amount": fmt(amount, 2, style) if show_prices else None, "amount_cents": cents(amount),
            "vat": str(p[4]) if "vat" in cols else None,
            "lot": (f"{r.choice(['L', '', 'LOT'])}{r.randint(10000, 9999999)}" if "lot" in cols else
                    (r.choice([f"{r.randint(100000, 999999)}", f"B{r.randint(2601, 2699)}-{r.randint(10000, 99999)}", f"L{r.randint(10000, 999999)}"])
                     if lot_place != "column" and r.random() < 0.9 else None)),
            "lot_place": lot_place,
            "vat_rate": p[4], "extra": None,
        })
        it = items[-1]
        it["category"] = p[2]
        if "store" in cols:
            codes = STORAGE[lang][0]
            letter = {"surgelati": r.choice(["S", "C"]), "carni": "F", "latticini": "F", "ortofrutta": "F"}.get(p[2], r.choice(["F", "CN", "ST"])) \
                if lang == "it" else {"surgelati": "F", "carni": "C", "latticini": "C"}.get(p[2], "A")
            it["store"], it["storage"] = letter, codes[letter]
        if row_types and it["code"] and r.random() < 0.4:
            letter = r.choice(list(ROW_TYPES[lang][0]))
            it["code_printed"], it["row_type"] = f"{letter} {it['code']}", ROW_TYPES[lang][0][letter]
        if "pack" in cols:
            ptype = r.choice(PACK_TYPES)
            psize = r.choice(["KG", "KG 1", "KG 2.5", "KG 11.3", "CL 75", "CL 150", "GR 500", "GR 2500", "ML 750", "PZ 1", "LT 4", ""])
            it["pack"], it["pack_type"], it["pack_size"] = f"{ptype} {psize}".strip(), ptype, psize or None
        if p[2] == "ortofrutta" and lang == "it" and not hand and r.random() < 0.6:
            origin = r.choice(ORIGINS); cat = r.choice(["I", "II"]); cal = r.choice(["", "50 -50", "100 -100", "120 -120", "14CM-14CM", "20 -20"])
            it["extra"] = f"Prov {origin} Cat {cat} Cal {cal}".strip()
            it["origin"], it["class"], it["caliber"] = origin, cat, cal or None
        elif r.random() < 0.08 and not hand:
            it["extra"] = r.choice(["ORIGINE: ITALIA", "ALLEVATO IN ITALIA", "NATURE'S BEST"] if lang == "it" else ["ORIGIN: ITALY", "FARMED"])
        if p[2] == "secco" and r.random() < 0.25:
            gross = r.choice([400, 800, 2550, 2500]); drained = int(gross * r.uniform(0.55, 0.7) / 10) * 10
            it["description"] += f" GR.{gross}({drained})X{r.choice([6, 3, 12])}"
            it["drained_g"], it["gross_g"] = drained, gross
    # Group lines ("Merce non deperibile - Congelato") over the items of each group, items sorted by group.
    grouped = kind in ("ddt", "invoice") and not hand and r.random() < 0.2
    if grouped:
        gmap = GROUPS[lang]
        for it in items:
            it["group"] = gmap.get(it.get("category"), gmap["_"])
        items.sort(key=lambda it: it["group"])
    for it in items:
        it.setdefault("code_printed", it["code"])
    key = {"code": "code_printed", "colli": "colli", "desc": "description", "unit": "unit", "qty": "quantity", "price": "price",
           "disc": "discount", "amount": "amount", "vat": "vat", "lot": "lot", "store": "store", "pack": "pack"}
    hsize = int(size * 0.85)
    fnt = load_font(hand_font if hand else font, int(size * 1.25) if hand else size, False)
    hfnt = load_font(font, hsize, True)
    two_row = r.random() < 0.25
    heading_texts = {c: r.choice(HEADINGS[lang][c]) for c in cols}
    def head_w(c):
        h = heading_texts[c]
        parts = h.split(" ", 1) if two_row and " " in h else [h]
        return max(hfnt.getlength(x) for x in parts)
    need = {}
    for c in cols:
        vals = [it[key[c]] for it in items if it.get(key[c])]
        need[c] = max([head_w(c)] + [fnt.getlength(v) for v in vals]) + r.randint(24, 60)
    avail = width - 2 * margin
    if sum(need.values()) > avail:
        k = avail / sum(need.values())
        need = {c: v * k for c, v in need.items()}
    else:
        need["desc"] += avail - sum(need.values()) - r.randint(0, 200)
    xs, x = {}, margin
    for c in cols:
        xs[c] = (x, x + need[c])
        x += need[c]
    numeric = {"qty", "price", "disc", "amount", "vat", "colli"}
    right_aligned = {c: c in numeric and r.random() < 0.85 for c in cols}
    def draw_headings(y):
        head_y = y
        for c in cols:
            h = heading_texts[c]
            x0, x1 = xs[c]
            def hx(t):
                return (x1 - 8, "right") if right_aligned[c] and r.random() < 0.5 else (x0 + 6, "left")
            if two_row and " " in h:
                a, b = h.split(" ", 1)
                px, al = hx(a)
                put(a, px, y, sz=hsize, bold=True, tag=f"heading:{c}", align=al)
                put(b, px, y + size, sz=hsize, bold=True, tag=f"heading:{c}", align=al)
            else:
                px, al = hx(h)
                put(h, px, y + (size * 0.5 if two_row else 0), sz=hsize, bold=True, tag=f"heading:{c}", align=al)
        if lot_heading:
            # The heading's second row, small, under the description heading: "ID LOTTO   QTA.LOT."
            put(r.choice(["ID LOTTO", "LOTTO", "N. LOTTO", "LOTTO / SCAD."] if lang == "it" else ["LOT", "BATCH NO."]),
                xs["desc"][0] + 6, y + size * (2.6 if two_row else 1.8) - size * 0.9, sz=int(hsize * 0.85), tag="heading:lot")
            y += size * 0.9
        y += size * (2.6 if two_row else 1.8)
        return y, head_y

    y, head_y = draw_headings(y)

    ruled = r.random() < 0.5
    def open_table(y, head_y):
        if ruled:
            doc.rules.append((doc.page, margin, head_y - 8, width - margin, head_y - 8))
            doc.rules.append((doc.page, margin, y - 10, width - margin, y - 10))

    def close_table(y, head_y):
        if ruled:
            for c in cols[1:]:
                doc.rules.append((doc.page, xs[c][0], head_y - 8, xs[c][0], y))
            doc.rules.append((doc.page, margin, y, width - margin, y))

    page_labels = []   # "PAG. 1" cells, completed with the page count at the end

    def new_page(continued):
        """The next sheet: a short header (supplier, VAT number, document, page) as invoicing programs repeat it."""
        doc.page += 1
        y = r.randint(70, 140)
        put(seller, margin, y, sz=int(size * 1.1), bold=True, tag="repeat:seller")
        put(f"{r.choice(L['vat_id'])} {seller_vat}", int(width * 0.55), y, sz=int(size * 0.85), tag="repeat:seller_vat")
        y += size * 1.8
        put(f"{title} {r.choice(L['number'])} {number} {r.choice(L['date'])} {date_text}", margin, y, bold=True, tag="repeat:number_date")
        page_labels.append(put("", width - margin, y, sz=int(size * 0.85), align="right", tag="page"))
        y += size * 2.4
        if continued:
            put(r.choice(CARRIED[lang]["from"]), margin, y, sz=int(size * 0.85), tag="continued")
            y += size * 1.4
        return y

    foot_room = r.randint(300, 900)   # the foot each sheet keeps free (some print totals boxes or notes there)
    if multi:
        page_labels.append(put("", width - margin, head_y - size * 1.3, sz=int(size * 0.85), align="right", tag="page"))
    open_table(y, head_y)
    last_group = None
    for i, item in enumerate(items):
        if multi and y > height - foot_room:
            # The page is full: "SEGUE" at its foot, the items go on under the same headings on the next.
            close_table(y, head_y)
            put(r.choice(CARRIED[lang]["to"]), int(width * 0.6), y + size * 1.2, bold=True, tag="continued")
            y = new_page(True)
            y, head_y = draw_headings(y)
            open_table(y, head_y)
            last_group = None
        item["page"] = doc.page
        if grouped and item["group"] != last_group:
            put(item["group"], xs["desc"][0] + 6, y, bold=r.random() < 0.5, tag=f"group:{i}")
            last_group = item["group"]
            y += size * 1.4
        row_y = y
        for c in cols:
            v = item[key[c]]
            if v is None:
                continue
            x0, x1 = xs[c]
            if right_aligned[c]:
                put(v, x1 - 10, row_y, tag=f"item:{i}:{c}", align="right", handwritten=hand)
            else:
                put(v, x0 + 6, row_y, tag=f"item:{i}:{c}", handwritten=hand)
        y += size * (1.9 if hand else 1.5)
        if item.get("lot") and lot_place != "column":
            lx = xs["code"][0] + 6 if lot_place == "under_code" else xs["desc"][0] + 6
            put(item["lot"], lx, y - size * 0.35, sz=int(size * 0.85), tag=f"item:{i}:lot")
            y += size * 1.1
        if item["extra"]:
            put(item["extra"], xs["desc"][0] + 40, y, sz=int(size * 0.8), tag=f"note:{i}")
            y += size * 1.3
    # Discount and charge lines.
    if show_prices and r.random() < 0.25:
        neg = r.random() < 0.6
        label = r.choice(L["discount_line"] if neg else L["charge_line"])
        v = round(r.uniform(2, 30), 2)
        amount = -v if neg else v
        items.append({"kind": "adjustment", "description": label, "amount": ("-" if neg else "") + fmt(v, 2, style),
                      "amount_cents": cents(amount), "quantity": None, "price": None, "unit": None, "code": None,
                      "colli": None, "discount": None, "vat": None, "lot": None, "vat_rate": 22})
        put(label, xs["desc"][0] + 6, y, tag=f"item:{len(items) - 1}:desc")
        put(items[-1]["amount"], xs["amount"][1] - 10, y, tag=f"item:{len(items) - 1}:amount", align="right")
        items[-1]["page"] = doc.page
        y += size * 1.5
    close_table(y, head_y)
    y += size * 2
    # No room left for the totals: they go on a page of their own.
    if multi and show_prices and y > height - 420:
        put(r.choice(CARRIED[lang]["to"]), int(width * 0.6), y, bold=True, tag="continued")
        y = new_page(False)

    # ---- totals
    truth_totals = {}
    totals_page = None
    if show_prices:
        totals_page = doc.page
        sub = sum(it["amount_cents"] for it in items)
        vat_by = {}
        for it in items:
            vat_by[it["vat_rate"]] = vat_by.get(it["vat_rate"], 0) + it["amount_cents"]
        vat = sum(int(round(b * rate / 100)) for rate, b in vat_by.items())
        total = sub + vat
        sl, vl, tl = r.choice(L["subtotal"]), r.choice(L["vat"]), r.choice(L["total"])
        p = lambda c: fmt(c / 100, 2, style)
        truth_totals = {"subtotal": p(sub), "vat": p(vat), "total": p(total), "subtotal_cents": sub, "vat_cents": vat, "total_cents": total}
        tx = int(width * 0.55)
        if r.random() < 0.5:
            for label, val, tag in ((sl, sub, "subtotal"), (vl, vat, "vat"), (tl, total, "total")):
                put(label, tx, y, bold=tag == "total", tag=f"label:{tag}")
                put(p(val), width - margin, y, bold=tag == "total", tag=f"field:{tag}", align="right", handwritten=hand)
                y += size * 1.5
        else:
            # A grid: labels in a row, the amounts under them.
            gx = [margin, margin + int(width * 0.3), margin + int(width * 0.55)]
            for (label, val, tag), gx_ in zip(((sl, sub, "subtotal"), (vl, vat, "vat"), (tl, total, "total")), gx):
                put(label, gx_, y, sz=int(size * 0.8), tag=f"label:{tag}")
                put(p(val), gx_ + 10, y + size * 1.2, tag=f"field:{tag}", handwritten=hand)
            y += size * 3
    y += size
    legends = []
    if "store" in cols:
        legends.append(r.choice(STORAGE[lang][1]))
    if row_types:
        legends.append(r.choice(ROW_TYPES[lang][1]))
    for lg in legends:
        put(lg, margin, y, sz=int(size * 0.8), tag="legend")
        y += size * 1.3
    if approval_text:
        put(approval_text, margin, y, sz=int(size * 0.75), tag="approval")
        y += size * 1.2
    for _ in range(r.randint(0, 3)):
        if y > height - 120:
            break
        put(r.choice(L["notes"]), margin, y, sz=int(size * 0.75))
        y += size * 1.2
    if multi and r.random() < 0.3:
        # A last page of sale conditions: the totals are not on the last page.
        y = new_page(False)
        for _ in range(r.randint(8, 16)):
            put(r.choice(L["notes"] + CONDITIONS[lang]), margin, y, sz=int(size * 0.75), tag="conditions")
            y += size * 1.2
    pages = doc.page + 1
    page_form = r.choice(["PAG. {k}", "PAG. {k}/{n}", "Pagina {k} di {n}" if lang == "it" else "Page {k} of {n}"])   # one program, one form
    for k, c in enumerate(page_labels):
        c.text = page_form.format(k=k + 1, n=pages)

    doc.truth = {
        "lang": lang, "style": style, "kind": kind, "seller": seller, "seller_vat": seller_vat, "customer": customer,
        "number": number, "date": f"{date[0]}-{date[1]:02d}-{date[2]:02d}", "date_text": date_text,
        "items": items, "totals": truth_totals, "headings": heading_texts, "columns": cols, "supplier_box": bool(supplier_box),
        "handwritten": hand, "lot_place": lot_place, "lot_heading": lot_heading, "legends": legends, "logo": logo,
        "approval_mark": approval_mark, "pages": pages, "totals_page": totals_page,
    }
    return doc


def receipt(r, lang, style, seller, seller_vat, number, date, date_text):
    width, height = 820, 1900
    doc = Doc(lang, "receipt", width, height)
    font = r.choice([f for f in PRINT_FONTS if "Mono" in f] + PRINT_FONTS[:2])
    size = r.randint(24, 28)
    m = 40
    y = 60

    def put(text, x, y, sz=None, bold=False, tag="", align="left"):
        doc.cells.append(Cell(text, x, y, sz or size, font, bold, False, tag, align))

    put(seller, m, y, bold=True, tag="field:seller"); y += size * 1.5
    put(f"{r.choice(STREETS)} {r.randint(1, 99)}", m, y); y += size * 1.3
    put(f"P.IVA {seller_vat}" if lang == "it" else f"VAT {seller_vat}", m, y, tag="field:seller_vat"); y += size * 2
    items = []
    for i in range(r.randint(2, 9)):
        p = r.choice(PRODUCTS)
        name = abbreviate(r, p[0] if lang == "it" else p[1])[:22]
        qty = r.randint(1, 6)
        price = round(r.uniform(*p[5]), 2)
        amount = round(qty * price, 2)
        it = {"kind": "product", "description": name, "quantity": str(qty) if qty > 1 else None, "price": fmt(price, 2, style) if qty > 1 else None,
              "amount": fmt(amount, 2, style), "amount_cents": cents(amount), "unit": None, "code": None, "colli": None,
              "discount": None, "vat": None, "lot": None, "vat_rate": None}
        items.append(it)
        # The name, then "4 x 4,53" under it with the amount on that line (or the amount beside the name).
        put(name, m, y, tag=f"item:{i}:desc")
        if qty > 1:
            y += size * 1.2
            put(f"{qty} x {it['price']}", m + 40, y, tag=f"item:{i}:qtyprice")
        put(it["amount"], width - m, y, tag=f"item:{i}:amount", align="right")
        y += size * 1.5
    total = sum(i["amount_cents"] for i in items)
    y += size
    put(r.choice(LABELS[lang]["receipt_total"]), m, y, bold=True, tag="label:total")
    put(fmt(total / 100, 2, style), width - m, y, bold=True, tag="field:total", align="right"); y += size * 2
    put(f"{date_text}  {r.randint(8, 22):02d}:{r.randint(0, 59):02d}", m, y, tag="field:date"); y += size * 1.3
    put(f"DOC. N. {number}" if lang == "it" else f"RECEIPT {number}", m, y, tag="field:number")
    doc.height = int(y + 120)
    doc.truth = {"lang": lang, "style": style, "kind": "receipt", "seller": seller, "seller_vat": seller_vat, "customer": None,
                 "number": number, "date": f"{date[0]}-{date[1]:02d}-{date[2]:02d}", "date_text": date_text, "items": items,
                 "totals": {"total": fmt(total / 100, 2, style), "total_cents": total}, "headings": {}, "columns": [],
                 "supplier_box": False, "handwritten": False}
    return doc


# ---------------------------------------------------------------------------------------------- drawing and the photo

_font_cache = {}


def load_font(path, size, bold):
    if bold:
        base = os.path.splitext(os.path.basename(path))[0]
        b = BOLD.get(base)
        if b:
            bold_path = os.path.join(os.path.dirname(path), b + os.path.splitext(path)[1])
            if os.path.exists(bold_path):   # no bold on this machine: the regular one
                path = bold_path
    key = (path, size)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(path, size)
    return _font_cache[key]


def draw(doc, r, page=0):
    """Renders one page; returns the image and every word's box on it (before the photo is taken)."""
    paper = tuple(int(v) for v in np.clip(np.array([250, 248, 242]) + r.randint(-10, 5), 220, 255))
    img = Image.new("RGB", (doc.width, doc.height), paper)
    d = ImageDraw.Draw(img)
    ink = (r.randint(0, 50),) * 3
    words = []   # (cell index, word text, x0, y0, x1, y1)
    for pg, x1, y1, x2, y2 in doc.rules:
        if pg != page:
            continue
        d.line([(x1, y1), (x2, y2)], fill=(90, 90, 90), width=2)
    for pg, kind_, x1, y1, x2, y2, lw in doc.shapes:
        if pg != page:
            continue
        (d.rectangle if kind_ == "rect" else d.ellipse)([x1, y1, x2, y2], outline=ink, width=lw)
    for ci, c in enumerate(doc.cells):
        if c.page != page:
            continue
        f = load_font(c.font, c.size if not c.hand else int(c.size * 1.25), c.bold)
        text_w = f.getlength(c.text)
        x = c.x - text_w if c.align == "right" else c.x
        color = (20, 30, 120) if c.hand and r.random() < 0.7 else ink
        if c.hand:
            # Handwriting: each word a little off the line, slanted a bit.
            cx = x
            for w in c.text.split(" "):
                ww = f.getlength(w)
                dy = r.uniform(-3, 3)
                d.text((cx, c.y + dy), w, font=f, fill=color)
                bb = d.textbbox((cx, c.y + dy), w, font=f)
                words.append((ci, w, bb[0], bb[1], bb[2], bb[3]))
                cx += ww + f.getlength(" ") * r.uniform(0.8, 1.6)
        else:
            d.text((x, c.y), c.text, font=f, fill=color)
            cx = x
            for w in c.text.split(" "):
                if w:
                    bb = d.textbbox((cx, c.y), w, font=f)
                    words.append((ci, w, bb[0], bb[1], bb[2], bb[3]))
                cx += f.getlength(w + " ")
    return img, words


class PhotoMap:
    """Where a point of the page lands on the photo: the perspective of the shot, then the bend of the paper (a sheet
    lifted at a corner or curled from being folded), then the scale the OCR reads at. Word boxes follow it exactly."""

    def __init__(self, M, bend, scale):
        self.M, self.bend, self.scale = M, bend, scale

    def points(self, pts):
        p = cv2.perspectiveTransform(np.float32([pts]), self.M)[0]
        if self.bend is not None:
            p = p + self.bend(p)
        return p * self.scale


def photograph(img, r, strength):
    """The page as a phone photo, with what real photos of supplier documents show: on a table (sometimes a red or
    white one), tilted, in perspective; the paper bent or curled, folded with creases, smudged; the light poor and
    uneven (a shadow across, a warm cast, dark corners); a finger holding it; the edge of another page under it with
    its own numbers; blur and noise. Returns the photo, the PhotoMap of the page onto it, and the words printed on
    other pages that show (the OCR reads them too; they are not part of the document)."""
    a = np.array(img)
    h, w = a.shape[:2]
    pad = int(0.06 * max(w, h))
    out_w, out_h = w + 2 * pad, h + 2 * pad
    k = 0.035 * strength
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    dst = np.float32([[pad + r.uniform(-k, k) * w, pad + r.uniform(-k, k) * h], [pad + w + r.uniform(-k, k) * w, pad + r.uniform(-k, k) * h],
                      [pad + w + r.uniform(-k, k) * w, pad + h + r.uniform(-k, k) * h], [pad + r.uniform(-k, k) * w, pad + h + r.uniform(-k, k) * h]])
    ang = math.radians(r.uniform(-3, 3) * strength)
    c = np.float32([out_w / 2, out_h / 2])
    rot = np.float32([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
    dst = (dst - c) @ rot.T + c
    M = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    table = tuple(int(v) for v in r.choice([(60, 50, 40), (120, 110, 100), (200, 200, 205), (40, 40, 45), (150, 130, 110),
                                            (190, 60, 60), (235, 235, 230), (200, 80, 75)]))
    rng = np.random.default_rng(r.randrange(1 << 30))

    # The surface, and possibly the edge of another page lying under this one, with its own print showing.
    background = np.zeros((out_h, out_w, 3), np.float32) + np.float32(table)
    mask = cv2.warpPerspective(np.ones((h, w), np.uint8) * 255, M, (out_w, out_h)) > 127
    others = []
    if r.random() < 0.35 * strength:
        side = r.choice(["left", "right"])
        strip_w = int(out_w * r.uniform(0.06, 0.14))
        x0 = 0 if side == "left" else out_w - strip_w
        background[:, x0:x0 + strip_w] = np.float32((245, 243, 238))
        bg = Image.fromarray(background.clip(0, 255).astype(np.uint8))
        d = ImageDraw.Draw(bg)
        f = load_font(PRINT_FONTS[0], r.randint(22, 34), False)
        y = r.randint(pad, pad + 200)
        while y < out_h - pad:
            t = r.choice([f"{r.randint(0, 99)},{r.randint(0, 999):03d}", f"{r.randint(1, 99)},{r.randint(0, 99):02d}", r.choice(["IMPO", "EUR", "COD", "PANNU", "LT1 -", "CRT -", "Cal 50"])])
            # Printed where the sheet under it shows (beside this page, not under it).
            free = np.where(~mask[min(y + 10, out_h - 1)])[0]
            free = free[(free >= x0) & (free < x0 + strip_w)]
            if len(free) > 0:
                lo, hi = int(free.min()), int(free.max())
                tw = f.getlength(t)
                if hi - lo > tw + 12:
                    tx = hi - tw - 6 if side == "left" else lo + 6
                    d.text((tx, y), t, font=f, fill=(40, 40, 40))
                    bb = [int(v) for v in d.textbbox((tx, y), t, font=f)]
                    if not mask[bb[1]:bb[3], bb[0]:bb[2]].any():
                        others.append((t, bb[0], bb[1], bb[2], bb[3]))
            y += r.randint(40, 70)
        background = np.array(bg).astype(np.float32)

    photo = cv2.warpPerspective(a, M, (out_w, out_h), flags=cv2.INTER_LINEAR)
    photo = np.where(mask[..., None], photo.astype(np.float32), background)

    # The paper bent: a corner lifted, or curled from a fold (a smooth shift of the print, a few tens of pixels).
    bend = None
    if r.random() < 0.6 * strength:
        A = r.uniform(8, 28) * strength
        kind = r.choice(["corner", "curl", "wave"])
        cx0, cy0 = r.choice([0, out_w]), r.choice([0, out_h])
        def bend(p, A=A, kind=kind, cx0=cx0, cy0=cy0):
            x, y = p[:, 0] / out_w, p[:, 1] / out_h
            if kind == "corner":
                dd = np.clip(1 - np.hypot(p[:, 0] - cx0, p[:, 1] - cy0) / (0.6 * max(out_w, out_h)), 0, 1) ** 2
                return np.stack([np.zeros_like(x), -A * dd], 1)
            if kind == "curl":
                return np.stack([np.zeros_like(x), A * (2 * x - 1) ** 2 * (y - 0.5) * 2], 1)
            return np.stack([np.zeros_like(x), A * 0.6 * np.sin(math.pi * 2 * x) * y], 1)
        yy, xx = np.mgrid[0:out_h, 0:out_w].astype(np.float32)
        q = np.stack([xx.ravel(), yy.ravel()], 1)
        dq = bend(q)
        mx = (q[:, 0] - dq[:, 0]).reshape(out_h, out_w).astype(np.float32)
        my = (q[:, 1] - dq[:, 1]).reshape(out_h, out_w).astype(np.float32)
        photo = cv2.remap(photo, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)

    yy, xx = np.mgrid[0:out_h, 0:out_w].astype(np.float32)
    # Fold creases: a thin bright edge and a soft shadow along a line across the sheet.
    for _ in range(r.choice([0, 0, 1, 1, 2]) if strength >= 0.6 else 0):
        if r.random() < 0.5:
            yc = r.uniform(0.25, 0.75) * out_h; dist = yy - yc
        else:
            xc = r.uniform(0.25, 0.75) * out_w; dist = xx - xc
        photo *= (1 - 0.12 * np.exp(-(dist / 18) ** 2) + 0.06 * np.exp(-((dist - 6) / 3) ** 2))[..., None]
    # Smudges and dirt: soft grey marks.
    for _ in range(r.choice([0, 0, 0, 2, 5]) if strength >= 0.6 else 0):
        mx0, my0 = r.uniform(0, out_w), r.uniform(0, out_h)
        rad = r.uniform(15, 90)
        photo *= (1 - r.uniform(0.05, 0.2) * np.exp(-(((xx - mx0) ** 2 + (yy - my0) ** 2) / rad ** 2)))[..., None]

    # Poor, uneven light: a gradient, a shadow across the page, dark corners, a warm or cold cast, low exposure.
    gx, gy = r.uniform(-1, 1), r.uniform(-1, 1)
    light = 1 + 0.18 * strength * ((xx / out_w - 0.5) * gx + (yy / out_h - 0.5) * gy)
    if r.random() < 0.5 * strength:
        sx = r.uniform(0.2, 0.8) * out_w; soft = r.uniform(5, 60)
        light *= 1 - r.uniform(0.1, 0.4) * strength / (1 + np.exp(np.clip(-(xx - sx) / soft, -50, 50)))
    if r.random() < 0.5 * strength:
        vig = ((xx / out_w - 0.5) ** 2 + (yy / out_h - 0.5) ** 2) * 2
        light *= 1 - r.uniform(0.1, 0.35) * vig
    photo *= light[..., None]
    if r.random() < 0.4 * strength:
        photo *= np.float32(r.choice([(1.06, 1.0, 0.86), (1.08, 0.98, 0.8), (0.92, 0.97, 1.08)]))
    photo = photo * r.uniform(0.75 if strength > 1 else 0.85, 1.1) + r.uniform(-25, 15)

    # A finger holding the sheet at an edge (outside the print, as people hold documents).
    if r.random() < 0.3 * strength:
        side = r.choice(["left", "bottom"])
        fx, fy = (r.uniform(0, 0.05) * out_w, r.uniform(0.4, 0.95) * out_h) if side == "left" else (r.uniform(0.05, 0.4) * out_w, out_h * r.uniform(0.97, 1.0))
        skin = np.float32(r.choice([(205, 160, 135), (170, 120, 95), (120, 85, 65), (225, 185, 160)]))
        fin = (((xx - fx) / r.uniform(45, 70)) ** 2 + ((yy - fy) / r.uniform(110, 170)) ** 2) < 1
        photo = np.where(fin[..., None], skin * (0.8 + 0.25 * light[..., None]), photo)

    photo += rng.normal(0, 3 + 6 * strength, photo.shape)
    photo = np.clip(photo, 0, 255).astype(np.uint8)
    blur = r.choice([0, 0, 1, 1, 2]) if strength > 0.5 else r.choice([0, 0, 1])
    if blur:
        photo = cv2.GaussianBlur(photo, (2 * blur + 1, 2 * blur + 1), 0)
    if strength > 1 and r.random() < 0.3:  # a shaky hand: a little motion blur
        kern = np.zeros((7, 7), np.float32); kern[3, :] = 1 / 7
        photo = cv2.filter2D(photo, -1, kern)
    # The OCR reads the page at 2400 px on its long side (as the app does).
    s = 2400 / max(out_w, out_h)
    photo = cv2.resize(photo, (int(out_w * s), int(out_h * s)), interpolation=cv2.INTER_AREA)
    other_words = []
    for t, x0, y0, x1, y1 in others:
        pts = np.float32([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
        if bend is not None:
            pts = pts + bend(pts)
        pts = pts * s
        other_words.append((t, int(pts[:, 0].min()), int(pts[:, 1].min()), int(math.ceil(pts[:, 0].max())), int(math.ceil(pts[:, 1].max()))))
    return photo, PhotoMap(M, bend, s), other_words


def warp_box(S, x0, y0, x1, y1):
    t = S.points([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
    return int(t[:, 0].min()), int(t[:, 1].min()), int(math.ceil(t[:, 0].max())), int(math.ceil(t[:, 1].max())), t


# ---------------------------------------------------------------------------------------------- what the OCR returns

LOGO_SLIPS = {"F": "E", "E": "F", "O": "Q", "C": "G", "G": "C", "M": "N", "N": "M", "R": "P", "P": "R", "B": "8", "I": "l"}

def slip(r, text, rate):
    """The OCR's usual mistakes on [text], at [rate] per character."""
    out, i = [], 0
    while i < len(text):
        two = text[i:i + 2]
        if two in SLIPS and r.random() < rate:
            out.append(SLIPS[two]); i += 2; continue
        ch = text[i]
        if ch in SLIPS and r.random() < rate:
            out.append(SLIPS[ch])
        elif ch == "," and r.random() < rate * 0.5:
            pass  # a lost decimal comma
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def reading(doc, words, S, r, strength):
    """ML Kit-like lines: the words of one printed run, a run split where the gap is wide; boxes on the photo."""
    by_cell = {}
    for ci, w, x0, y0, x1, y1 in words:
        by_cell.setdefault(ci, []).append((w, x0, y0, x1, y1))
    lines = []
    for ci, ws in by_cell.items():
        cell = doc.cells[ci]
        rate = (0.06 if cell.hand else 0.004 + 0.012 * strength)
        out_words = []
        for w, x0, y0, x1, y1 in ws:
            t = slip(r, w, rate)
            if cell.tag == "logo" and r.random() < 0.3:
                # A stylised logo letter read as another (ABF read ABE): the logo is a picture, not print.
                t = "".join(LOGO_SLIPS.get(ch, ch) if r.random() < 0.5 else ch for ch in t)
            if cell.tag.startswith("heading:") and len(w) >= 4 and r.random() < 0.06 * strength:
                t = t + r.choice(["0", ".", "1", "'"])   # a stray character on a heading word (LOTTO0)
            if cell.tag.startswith("item:") and doc.rules and r.random() < 0.04:
                t = t + r.choice(["|", ")", "]"])  # a table rule read as a character
            conf = r.uniform(0.25, 0.65) if cell.hand else (r.uniform(0.45, 0.8) if t != w else r.uniform(0.86, 0.99))
            bx0, by0, bx1, by1, _ = warp_box(S, x0, y0, x1, y1)
            out_words.append((t, bx0, by0, bx1, by1, conf))
        if not out_words:
            continue
        x0 = min(o[1] for o in out_words); y0 = min(o[2] for o in out_words)
        x1 = max(o[3] for o in out_words); y1 = max(o[4] for o in out_words)
        _, _, _, _, corners = warp_box(S, ws[0][1], ws[0][2], ws[-1][3], ws[-1][4])
        ang = math.degrees(math.atan2(corners[1][1] - corners[0][1], corners[1][0] - corners[0][0]))
        lines.append({"text": " ".join(o[0] for o in out_words), "box": [x0, y0, x1, y1], "angle": round(ang, 1),
                      "conf": round(min(o[5] for o in out_words), 2), "words": out_words, "tag": cell.tag})
    # ML Kit sometimes runs two cells close together into one line.
    lines.sort(key=lambda l: (l["box"][1], l["box"][0]))
    return lines


def report(lines):
    """The app's "Raw lines" report format (what the parser and the exporter read)."""
    out = ["=== Raw lines, page 1 (left,top,right,bottom,angle) ==="]
    for l in lines:
        x0, y0, x1, y1 = l["box"]
        conf = f",{l['conf']:.2f}" if l["conf"] < 1 else ""
        s = f"{x0},{y0},{x1},{y1},{l['angle']:.1f}{conf} | {l['text']}"
        if len(l["words"]) > 1 or any(w[5] < 0.8 for w in l["words"]):
            s += "  [" + " ".join(f"{w[1]}-{w[3]}:{w[0]}" + (f"@{w[5]:.2f}" if w[5] < 0.8 else "") for w in l["words"]) + "]"
        out.append(s)
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--count", type=int, default=20)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--fonts", help="folder with handwriting .ttf/.otf fonts (Google Fonts: Caveat, Kalam, ...)")
    args = ap.parse_args()
    hand = []
    if args.fonts and os.path.isdir(args.fonts):
        hand = [os.path.join(args.fonts, f) for f in sorted(os.listdir(args.fonts)) if f.lower().endswith((".ttf", ".otf"))]
    # Only fonts that open on this machine (a missing or broken file would stop the whole run).
    PRINT_FONTS[:] = usable(PRINT_FONTS)
    if not PRINT_FONTS:
        sys.exit("No print font found: install fonts-liberation / fonts-dejavu-core (apt-get).")
    args.hand_fonts = usable(hand) or usable(FALLBACK_HAND) or [PRINT_FONTS[0]]
    print(f"fonts: {len(PRINT_FONTS)} print, {len(args.hand_fonts)} handwriting")
    os.makedirs(args.out, exist_ok=True)
    made = failed = 0
    for k in range(args.count):
        seed = args.seed * 1_000_003 + k
        r = random.Random(seed)
        try:
            write_doc(r, seed, args)
            made += 1
        except Exception as e:   # one bad document is skipped, not the whole run; many is an error
            failed += 1
            if failed <= 3:
                print(f"document {seed} skipped: {type(e).__name__}: {e}", file=sys.stderr)
    print(f"{made} documents in {args.out}" + (f" ({failed} skipped)" if failed else ""))
    if made < args.count * 0.9:
        sys.exit(f"Only {made} of {args.count} documents made: see the errors above.")


def usable(paths):
    ok = []
    for p in paths:
        try:
            ImageFont.truetype(p, 20)
            ok.append(p)
        except Exception:
            pass
    return ok


def write_doc(r, seed, args):
    doc = make_doc(r, args)
    pages = doc.truth.get("pages", 1)
    strength = r.choice([0.3, 0.6, 1.0, 1.0, 1.4])
    d = os.path.join(args.out, f"doc{seed}")
    os.makedirs(d, exist_ok=True)
    rows, boxes, photos = {}, {}, []
    for page in range(pages):
        # Each sheet photographed on its own: its own angle, light and bend.
        img, words = draw(doc, r, page)
        photo, S, other_words = photograph(img, r, strength)
        lines = reading(doc, words, S, r, strength)
        # Print of another page showing at the edge of the photo: the OCR reads it too (not part of the document).
        for t, x0, y0, x1, y1 in other_words:
            lines.append({"text": t, "box": [x0, y0, x1, y1], "angle": 0.0, "conf": round(r.uniform(0.6, 0.95), 2),
                          "words": [(t, x0, y0, x1, y1, 0.9)], "tag": "other-page"})
        lines.sort(key=lambda l: (l["box"][1], l["box"][0]))
        # Where each item's row is on its photo (to know which question is about which line).
        for l in lines:
            if l["tag"].startswith("item:"):
                i = int(l["tag"].split(":")[1])
                b = rows.setdefault(i, list(l["box"]))
                rows[i] = [min(b[0], l["box"][0]), min(b[1], l["box"][1]), max(b[2], l["box"][2]), max(b[3], l["box"][3])]
            # The labelled areas, on the page they are printed on (the totals may be on a later page).
            if l["tag"].startswith(("heading:", "box:", "field:", "label:")):
                boxes.setdefault(l["tag"], []).append(l["box"] + [page])
        photos.append({"width": photo.shape[1], "height": photo.shape[0]})
        name = "" if page == 0 else str(page + 1)
        cv2.imwrite(os.path.join(d, f"page{name}.jpg"), cv2.cvtColor(photo, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, r.randint(55, 90)])
        with open(os.path.join(d, f"reading{name}.txt"), "w") as f:
            f.write(report(lines))
    for i, it in enumerate(doc.truth["items"]):
        it["box"] = rows.get(i)
        it.setdefault("page", 0)
    doc.truth["boxes"] = boxes
    doc.truth["photo"] = dict(photos[0], strength=strength)
    doc.truth["photos"] = photos
    with open(os.path.join(d, "truth.json"), "w") as f:
        json.dump(doc.truth, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
