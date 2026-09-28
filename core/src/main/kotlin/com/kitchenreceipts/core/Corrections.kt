package com.kitchenreceipts.core

/**
 * Compares what the OCR proposed with what the operator saved. The result goes into the
 * diagnostic log: it shows exactly which fields the reader gets wrong, per supplier.
 */
object Corrections {

    fun diff(initial: DocumentDraft, final: DocumentDraft): List<String> {
        val out = mutableListOf<String>()
        fun cmp(name: String, a: DraftField, b: DraftField) {
            val x = a.text.trim(); val y = b.text.trim()
            if (x != y) out += "$name: ${show(x)} -> ${show(y)}"
            else if (a.uncertain && !b.uncertain && x.isNotEmpty()) out += "$name: confirmed ${show(x)}"
        }
        cmp("seller", initial.seller, final.seller)
        cmp("date", initial.date, final.date)
        cmp("number", initial.number, final.number)
        cmp("currency", initial.currency, final.currency)
        cmp("subtotal", initial.subtotal, final.subtotal)
        cmp("vat", initial.vat, final.vat)
        cmp("total", initial.total, final.total)
        if (initial.vatBasis != final.vatBasis) out += "vatBasis: ${initial.vatBasis} -> ${final.vatBasis}"

        val before = initial.items.associateBy { it.key }
        val after = final.items.associateBy { it.key }
        initial.items.filter { it.key !in after }.forEach { out += "item removed: ${show(it.description.text)} ${show(it.lineTotal.text)}" }
        final.items.filter { it.key !in before }.forEach { out += "item added: ${show(it.description.text)} ${show(it.lineTotal.text)}" }
        for (b in final.items) {
            val a = before[b.key] ?: continue
            val label = "item '${a.description.text.take(30)}'"
            fun c(name: String, x: DraftField, y: DraftField) {
                if (x.text.trim() != y.text.trim()) out += "$label $name: ${show(x.text.trim())} -> ${show(y.text.trim())}"
            }
            c("description", a.description, b.description)
            c("qty", a.quantity, b.quantity)
            c("unit", a.unit, b.unit)
            c("price", a.unitPrice, b.unitPrice)
            c("total", a.lineTotal, b.lineTotal)
            c("vat%", a.vatRate, b.vatRate)
            c("lot", a.lot, b.lot)
            c("expiry", a.expiry, b.expiry)
        }
        return out
    }

    private fun show(s: String) = if (s.isEmpty()) "(missing)" else "'$s'"
}
