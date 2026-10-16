package com.kitchenreceipts.core

/**
 * The review screen's draft as text, so the operator's corrections survive leaving the screen, the app lock and
 * Android closing the app. Everything the operator can change is kept exactly (every field's text and whether it
 * is still unchecked, the products chosen, lines added or removed). What only helps the screen (the readings
 * offered under a doubtful line, the AI's other reading, the VAT groups to look at) is taken again from [hints],
 * the same document read again, matched by line key.
 */
object DraftCodec {
    private const val VERSION = 1

    fun encode(d: DocumentDraft): String = buildString {
        append('{')
        kv("v", VERSION); append(',')
        field("seller", d.seller); append(','); field("date", d.date); append(','); field("number", d.number); append(',')
        field("currency", d.currency); append(','); field("subtotal", d.subtotal); append(','); field("vat", d.vat); append(',')
        field("total", d.total); append(',')
        kv("vatBasis", d.vatBasis.name); append(','); kv("vatBasisUncertain", d.vatBasisUncertain); append(',')
        kv("lotsPrinted", d.lotsPrinted); append(',')
        str("items"); append(":[")
        d.items.forEachIndexed { i, it ->
            if (i > 0) append(',')
            append('{')
            kv("key", it.key); append(',')
            field("description", it.description); append(','); field("quantity", it.quantity); append(',')
            field("unit", it.unit); append(','); field("unitPrice", it.unitPrice); append(',')
            field("lineTotal", it.lineTotal); append(','); field("vatRate", it.vatRate); append(',')
            field("lot", it.lot); append(','); field("expiry", it.expiry); append(',')
            field("packages", it.packages); append(','); field("packSize", it.packSize); append(',')
            field("discount", it.discount); append(',')
            kv("productId", it.productId); append(','); kv("productName", it.productName); append(',')
            kv("productSource", it.productSource?.name); append(',')
            kv("newProductName", it.newProductName); append(','); kv("newProductBrand", it.newProductBrand); append(',')
            kv("itemCode", it.itemCode); append(','); kv("adjustment", it.adjustment); append(',')
            kv("hasChoices", it.choices.isNotEmpty()); append(',')
            kv("marks", ItemMark.encode(it.marks))
            append('}')
        }
        append("]}")
    }

    /** The draft written by [encode], or null when the text is not one (damaged, or from a newer app). */
    fun decode(text: String, hints: DocumentDraft? = null): DocumentDraft? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse(text) as Map<String, Any?>
        if ((m["v"] as? String)?.toIntOrNull() != VERSION) return null
        val byKey = hints?.items.orEmpty().associateBy { it.key }
        @Suppress("UNCHECKED_CAST")
        val items = (m["items"] as List<Map<String, Any?>>).map { i ->
            val key = (i["key"] as String).toLong()
            val hint = byKey[key]
            LineItemDraft(
                key = key,
                description = field(i["description"]), quantity = field(i["quantity"]), unit = field(i["unit"]),
                unitPrice = field(i["unitPrice"]), lineTotal = field(i["lineTotal"]), vatRate = field(i["vatRate"]),
                lot = field(i["lot"]), expiry = field(i["expiry"]), packages = field(i["packages"]),
                packSize = field(i["packSize"]), discount = field(i["discount"]),
                productId = (i["productId"] as? String)?.toLong(),
                productName = i["productName"] as? String,
                productSource = (i["productSource"] as? String)?.let { ProductSource.valueOf(it) },
                newProductName = i["newProductName"] as? String,
                newProductBrand = i["newProductBrand"] as? String,
                itemCode = i["itemCode"] as? String,
                adjustment = i["adjustment"] == true,
                marks = ItemMark.decode(i["marks"] as? String),
                // Offered readings come back only while the operator had not settled the line.
                choices = if (i["hasChoices"] == true) hint?.choices.orEmpty() else emptyList(),
                aiRead = hint?.aiRead.orEmpty(),
                nameUnknown = hint?.nameUnknown.orEmpty(),
            )
        }
        val seller = field(m["seller"])
        DocumentDraft(
            seller = seller, date = field(m["date"]), number = field(m["number"]), currency = field(m["currency"]),
            subtotal = field(m["subtotal"]), vat = field(m["vat"]), total = field(m["total"]),
            vatBasis = VatBasis.valueOf(m["vatBasis"] as String),
            vatBasisUncertain = m["vatBasisUncertain"] == true,
            lotsPrinted = m["lotsPrinted"] != false,
            items = items,
            warnings = hints?.warnings.orEmpty(),
            vatGroupProblems = hints?.vatGroupProblems.orEmpty(),
            aiCheck = hints?.aiCheck,
            sellerAlternatives = if (seller.uncertain) hints?.sellerAlternatives.orEmpty() else emptyList(),
            checks = hints?.checks,
        ).let { d -> if (hints == null) d else d.checksStillFor(hints) }
    }.getOrNull()

    private fun field(v: Any?): DraftField {
        @Suppress("UNCHECKED_CAST")
        val m = v as Map<String, Any?>
        return DraftField(m["t"] as String, m["u"] == true, m["s"] as? String)
    }

    private fun StringBuilder.field(name: String, f: DraftField) {
        str(name); append(":{"); kv("t", f.text); append(','); kv("u", f.uncertain); append(','); kv("s", f.source); append('}')
    }

    private fun StringBuilder.kv(name: String, v: Any?) {
        str(name); append(':')
        when (v) {
            null -> append("null")
            is Boolean -> append(v)
            // Numbers as text: the reader keeps them exactly (a line key or product id never goes through a double).
            is Number -> str(v.toString())
            else -> str(v.toString())
        }
    }

    private fun StringBuilder.str(s: String) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
