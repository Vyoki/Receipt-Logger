package com.kitchenreceipts.core

/**
 * Lots printed at the end of the product's name, with no "Lotto" before them ("ROASTBEEF B.AD.TR.S/V AB500168"):
 * some suppliers' systems append the batch to the article's text. Such a code is letters then digits, at the very
 * end of the name, and is no pack size ("G500", "CL33", "KG4") and no article code.
 *
 * Nothing says it is a lot, so it is only offered: highlighted until the operator confirms it. Once the operator has
 * kept such lots on a document of this supplier ([SupplierLayout.lotsInNames]), the supplier is known to print them
 * this way and they are taken as read.
 */
object LotsInNames {

    /** How the lots found here are marked (their source starts with it). */
    const val SOURCE = "lot at the end of the name"

    /** Letters (1 to 3) then digits; the camera's usual letters for digits (O, S, I, l) allowed among the digits. */
    private val BATCH = rx("^([A-Z]{1,3})([0-9OSIl]{4,10})$")
    /** Pack sizes and units written before a number ("G500", "GR250", "CL33", "PZ100", "X12", "N10"). */
    private val SIZE_PREFIX = setOf(
        "G", "GR", "KG", "CC", "CL", "ML", "LT", "L", "P", "PZ", "X", "N", "NR", "CF", "MM", "CM", "CT", "CRT", "PK",
        "F", "FT", "H", "M", "MT", "CA", "C", "CS", "BT", "SC", "CONF", "VS", "TB", "SQ", "PET",
    )

    /** The batch-like code that ends [name], or null. */
    fun candidate(name: String): String? {
        val words = name.trim().split(rx("\\s+"))
        if (words.size < 3 || words.dropLast(1).sumOf { w -> w.count(Char::isLetter) } < 4) return null
        val t = words.last().trimEnd('.', ',', '-', ';').uppercase()
        val m = BATCH.matchEntire(t) ?: return null
        if (m.groupValues[1] in SIZE_PREFIX || m.groupValues[2].count(Char::isDigit) < 3 || LotExtractor.neverALot(t)) return null
        return t
    }

    fun apply(doc: ParsedDocument, layout: SupplierLayout?): ParsedDocument {
        val codes = doc.lineItems.mapNotNull { it.itemCode?.uppercase() }.toSet()
        val found = doc.lineItems.map { item ->
            if (item.lotNumber != null || item.adjustment) null
            else candidate(item.originalDescription)?.takeIf { it !in codes }
        }
        if (found.all { it == null }) return doc
        // The same article printed twice reads its batch differently ("AB500168", "ABSO0168"): the reading with the
        // most digits is the one the camera read best.
        // The rows the numbers were read from count too (a name tidied to another line's spelling keeps its own there).
        val printed = doc.lineItems.flatMap { listOfNotNull(it.lineTotalCents?.source, it.quantity?.source) }
            .flatMap { it.uppercase().split(rx("\\s+")) }.map { it.trimEnd('.', ',', '-', ';') }.filter { BATCH.matches(it) }
        val keys = found.filterNotNull().map { lotKey(it) }.toSet()
        val best = (found.filterNotNull() + printed.filter { lotKey(it) in keys }).groupBy { lotKey(it) }
            .mapValues { (_, v) -> v.maxBy { s -> s.count(Char::isDigit) } }
        val sure = layout?.lotsInNames == true
        val items = doc.lineItems.mapIndexed { i, item ->
            val raw = found[i] ?: return@mapIndexed item
            val lot = best[lotKey(raw)] ?: raw
            val name = item.originalDescription.trim().split(rx("\\s+")).dropLast(1).joinToString(" ")
            item.copy(
                originalDescription = name,
                lotNumber = Extracted(lot, if (sure) Confidence.HIGH else Confidence.LOW, "$SOURCE: ${item.originalDescription}"),
            )
        }
        return doc.copy(lineItems = items, lotsInNames = true)
    }

    /** One batch however the camera read it: the slips folded to digits. */
    private fun lotKey(s: String) = s.map { c -> when (c) { 'O' -> '0'; 'S' -> '5'; 'I', 'l' -> '1'; else -> c } }.joinToString("")
}
