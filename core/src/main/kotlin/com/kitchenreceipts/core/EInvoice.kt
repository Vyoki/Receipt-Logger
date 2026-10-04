package com.kitchenreceipts.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Italian electronic invoices (FatturaPA, the XML every supplier sends through the SdI): read exactly, no OCR.
 *
 * Accepts the plain .xml, the signed .p7m (DER or base64) and a .zip of several. Every value comes from its XML
 * element, so everything is sure; a value the XML does not carry (a quantity on a service line) stays empty.
 * Works for any supplier: only the standard's element names are used, whatever namespace prefix the file has.
 */
object EInvoice {

    data class Read(
        val parsed: ParsedDocument,
        /** Seller's VAT number ("IT01234567897"). */
        val sellerVat: String?,
        /** Buyer's VAT number or tax code, as written. */
        val buyerId: String?,
        /** TD01 invoice, TD04 credit note, TD24 deferred invoice... */
        val documentType: String?,
        /** A credit note (TD04, TD08): money back, not a purchase. */
        val creditNote: Boolean,
        /** The invoice laid out as text: shown as the document's page and searched like scanned text. */
        val text: String,
    )

    class NotAnEInvoice(message: String) : Exception(message)

    private const val MAX_UNZIPPED = 50L * 1024 * 1024
    private const val MAX_FILES = 500

    // ---------------------------------------------------------------- recognising and unwrapping

    /** True when the bytes are an e-invoice in any accepted wrapping (XML, signed p7m, base64 p7m) or a zip. */
    fun looksLikeEInvoice(bytes: ByteArray): Boolean = isZip(bytes) || runCatching { unwrap(bytes) }.getOrNull() != null

    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    /**
     * The invoices in a file: one XML per invoice (a file with several invoice bodies is split, each keeping the
     * shared header). A zip gives all the invoices inside it; files that are not invoices (SdI receipts) are skipped.
     */
    fun invoices(bytes: ByteArray): List<ByteArray> {
        if (isZip(bytes)) return unzip(bytes).flatMap { runCatching { invoices(it) }.getOrDefault(emptyList()) }
        val xml = unwrap(bytes) ?: throw NotAnEInvoice("Not an e-invoice")
        return split(xml)
    }

    /** The XML inside the bytes: as is, inside a signed p7m envelope, or base64 of either. Null when there is none. */
    fun unwrap(bytes: ByteArray): ByteArray? {
        val start = firstNonBlank(bytes)
        if (start < bytes.size && bytes[start] == '<'.code.toByte()) return if (containsInvoice(bytes)) bytes else null
        if (start < bytes.size && (bytes[start].toInt() and 0xFF) == 0x30) {
            return runCatching { octetStrings(bytes) }.getOrNull()?.firstOrNull { containsInvoice(it) }?.let { inner -> unwrap(inner) ?: inner }
        }
        // Base64 text (some systems send the p7m that way), possibly with PEM lines.
        val text = String(bytes, Charsets.US_ASCII).lines().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
        if (text.length >= 16 && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            val decoded = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
            if (decoded.isNotEmpty() && decoded[0] != bytes[start]) return unwrap(decoded)
        }
        return null
    }

    private fun firstNonBlank(b: ByteArray): Int {
        var i = 0
        // UTF-8 byte order mark
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF && (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF) i = 3
        while (i < b.size && (b[i] == ' '.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte() || b[i] == '\t'.code.toByte())) i++
        return i
    }

    private fun containsInvoice(b: ByteArray): Boolean {
        val s = String(b, Charsets.ISO_8859_1)
        return s.contains("FatturaElettronicaBody") || s.contains("FatturaElettronicaSemplificata") || s.contains("FatturaElettronicaHeader")
    }

    /**
     * The contents of every OCTET STRING in a DER/BER structure (a signed p7m), with chunked (constructed) ones joined.
     * Only the structure is walked: the signature is not verified (the SdI did that before delivering the file).
     */
    private fun octetStrings(b: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        fun walk(from: Int, to: Int, depth: Int, sink: ByteArrayOutputStream?): Int {
            var p = from
            while (p < to) {
                if (depth > 40) throw IllegalStateException("too deep")
                if (p + 1 < to && b[p].toInt() == 0 && b[p + 1].toInt() == 0) return p + 2 // end of an indefinite length
                val tag = b[p].toInt() and 0xFF
                p++
                if (tag and 0x1F == 0x1F) { while (p < to && b[p].toInt() and 0x80 != 0) p++; p++ }
                if (p >= to) throw IllegalStateException("truncated")
                var len = b[p].toInt() and 0xFF
                p++
                var indefinite = false
                if (len == 0x80) indefinite = true
                else if (len > 0x80) {
                    val n = len and 0x7F
                    if (n > 4) throw IllegalStateException("length")
                    len = 0
                    repeat(n) { len = (len shl 8) or (b[p++].toInt() and 0xFF) }
                }
                val constructed = tag and 0x20 != 0
                val isOctets = tag and 0x1F == 0x04
                if (constructed) {
                    val chunks = if (isOctets) (sink ?: ByteArrayOutputStream()) else null
                    val end = if (indefinite) walk(p, to, depth + 1, chunks) else { walk(p, p + len, depth + 1, chunks); p + len }
                    if (isOctets && sink == null) out += chunks!!.toByteArray()
                    p = end
                } else {
                    if (indefinite || p + len > to) throw IllegalStateException("bad length")
                    if (isOctets) { if (sink != null) sink.write(b, p, len) else out += b.copyOfRange(p, p + len) }
                    p += len
                }
            }
            return p
        }
        walk(0, b.size, 0, null)
        return out
    }

    private fun unzip(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (out.size >= MAX_FILES) break
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = z.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UNZIPPED) throw NotAnEInvoice("The zip holds more than ${MAX_UNZIPPED / 1024 / 1024} MB")
                    buf.write(chunk, 0, n)
                }
                val name = e.name.lowercase()
                if (name.endsWith(".zip")) out += unzip(buf.toByteArray()) else out += buf.toByteArray()
            }
        }
        return out
    }

    // ---------------------------------------------------------------- XML helpers

    private fun parseXml(xml: ByteArray): Document {
        // No DOCTYPE in an e-invoice: refusing it rules out entity tricks.
        if (String(xml, Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true)) throw NotAnEInvoice("Unexpected DOCTYPE")
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun Node.local(): String = (localName ?: nodeName).substringAfter(':')

    private fun Element.kids(name: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.local() == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()

    /** Element at a path of child names ("DatiAnagrafici/Anagrafica/Denominazione"). */
    private fun Element.at(path: String): Element? = path.split('/').fold(this as Element?) { e, n -> e?.kid(n) }

    private fun Element.text(path: String): String? = at(path)?.textContent?.trim()?.ifEmpty { null }

    private fun root(doc: Document): Element = doc.documentElement

    /** Splits a file with several invoice bodies into one XML per invoice (header shared). */
    private fun split(xml: ByteArray): List<ByteArray> {
        val doc = parseXml(xml)
        val bodies = root(doc).kids("FatturaElettronicaBody")
        if (bodies.size <= 1) return listOf(xml)
        return bodies.indices.map { keep ->
            val copy = doc.cloneNode(true) as Document
            root(copy).kids("FatturaElettronicaBody").forEachIndexed { i, b -> if (i != keep) b.parentNode.removeChild(b) }
            val out = ByteArrayOutputStream()
            val t = TransformerFactory.newInstance().newTransformer()
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            t.transform(DOMSource(copy), StreamResult(out))
            out.toByteArray()
        }
    }

    // ---------------------------------------------------------------- reading

    private fun dec(s: String?): BigDecimal? = s?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
    private fun cents(d: BigDecimal): Long = d.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    private fun date(s: String?): LocalDate? = s?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun <T> sure(v: T, src: String) = Extracted(v, Confidence.HIGH, src)
    private fun money(c: Long) = ItalianNumbers.formatCents(c)
    private fun num(d: BigDecimal) = ItalianNumbers.formatDecimal(d, maxScale = 4)

    private fun party(e: Element?): Pair<String?, String?> {
        if (e == null) return null to null
        val name = e.text("DatiAnagrafici/Anagrafica/Denominazione")
            ?: listOfNotNull(e.text("DatiAnagrafici/Anagrafica/Nome"), e.text("DatiAnagrafici/Anagrafica/Cognome")).joinToString(" ").ifBlank { null }
            ?: e.text("Denominazione") // simplified invoice
            ?: listOfNotNull(e.text("Nome"), e.text("Cognome")).joinToString(" ").ifBlank { null }
        val id = (e.at("DatiAnagrafici/IdFiscaleIVA") ?: e.at("IdFiscaleIVA"))?.let { v -> (v.text("IdPaese") ?: "") + (v.text("IdCodice") ?: "") }?.ifBlank { null }
            ?: e.text("DatiAnagrafici/CodiceFiscale") ?: e.text("CodiceFiscale")
        return name to id
    }

    private val CREDIT_NOTES = setOf("TD04", "TD08")
    private val DOC_NAMES = mapOf(
        "TD01" to "FATTURA", "TD02" to "ACCONTO SU FATTURA", "TD04" to "NOTA DI CREDITO", "TD05" to "NOTA DI DEBITO",
        "TD06" to "PARCELLA", "TD07" to "FATTURA SEMPLIFICATA", "TD08" to "NOTA DI CREDITO SEMPLIFICATA",
        "TD24" to "FATTURA DIFFERITA", "TD25" to "FATTURA DIFFERITA",
    )

    /**
     * Reads one invoice. [ownVat]: the restaurant's VAT number, to notice an invoice addressed to someone else.
     */
    fun read(xml: ByteArray, ownVat: String? = null): Read {
        val doc = parseXml(xml)
        val r = root(doc)
        if (!r.local().startsWith("FatturaElettronica")) throw NotAnEInvoice("Not an e-invoice (${r.local()})")
        val simplified = r.local() == "FatturaElettronicaSemplificata"
        val header = r.kid("FatturaElettronicaHeader") ?: throw NotAnEInvoice("No invoice header")
        val body = r.kids("FatturaElettronicaBody").firstOrNull() ?: throw NotAnEInvoice("No invoice body")
        val (seller, sellerVat) = party(header.kid("CedentePrestatore"))
        val (buyer, buyerId) = party(header.kid("CessionarioCommittente"))
        val gen = body.at("DatiGenerali/DatiGeneraliDocumento")
        val type = gen?.text("TipoDocumento")
        val number = gen?.text("Numero")
        val docDate = date(gen?.text("Data"))
        val currency = gen?.text("Divisa") ?: "EUR"
        val printedTotal = dec(gen?.text("ImportoTotaleDocumento"))
        val ddts = body.at("DatiGenerali")?.kids("DatiDDT").orEmpty().map { d -> listOfNotNull(d.text("NumeroDDT"), date(d.text("DataDDT"))?.let(ItalianDates::format)).joinToString(" del ") }

        val text = StringBuilder()
        val title = DOC_NAMES[type] ?: "FATTURA"
        text.append("$title ELETTRONICA").append(type?.let { " ($it)" } ?: "").append('\n')
        text.append(seller ?: "").append('\n')
        sellerVat?.let { text.append("P.IVA ").append(it).append('\n') }
        header.text("CedentePrestatore/Sede/Indirizzo")?.let { a ->
            text.append(listOfNotNull(a, header.text("CedentePrestatore/Sede/NumeroCivico"), header.text("CedentePrestatore/Sede/CAP"), header.text("CedentePrestatore/Sede/Comune")).joinToString(" ")).append('\n')
        }
        text.append("Cliente: ").append(buyer ?: "").append(buyerId?.let { "  P.IVA/C.F. $it" } ?: "").append('\n')
        text.append("Numero ").append(number ?: "").append(" del ").append(docDate?.let(ItalianDates::format) ?: "").append('\n')
        if (ddts.isNotEmpty()) text.append("DDT: ").append(ddts.joinToString(", ")).append('\n')
        text.append('\n')

        val items = mutableListOf<ParsedLineItem>()
        val groups = linkedMapOf<BigDecimal, Long>() // rate -> lines' sum (cents)
        val groupLines = mutableMapOf<BigDecimal, Int>()
        if (!simplified) {
            for (line in body.at("DatiBeniServizi")?.kids("DettaglioLinee").orEmpty()) {
                val raw = line.text("Descrizione") ?: ""
                val qty = dec(line.text("Quantita"))
                val price = dec(line.text("PrezzoUnitario"))
                val totalDec = dec(line.text("PrezzoTotale")) ?: continue
                val total = cents(totalDec)
                val rate = dec(line.text("AliquotaIVA")) ?: BigDecimal.ZERO
                // A text-only line ("Rif. DDT 123 del ...", "Trasporto incluso"): nothing bought, nothing to count.
                if (total == 0L && (qty == null || qty.signum() == 0)) {
                    if (raw.isNotBlank()) text.append("   ").append(raw).append('\n')
                    continue
                }
                val src = "${line.text("NumeroLinea") ?: (items.size + 1)}. $raw"
                // Lots and expiry dates: in the structured "other data" (TipoDato LOTTO / SCADENZA) or in the description.
                val other = line.kids("AltriDatiGestionali")
                val lotData = other.firstOrNull { (it.text("TipoDato") ?: "").uppercase().let { t -> "LOT" in t || "BATCH" in t } }
                val expData = other.firstOrNull { (it.text("TipoDato") ?: "").uppercase().let { t -> "SCAD" in t || "EXP" in t } }
                val scan = LotExtractor.scan(raw)
                val description = LotExtractor.strip(raw, scan.consumed).replace(Regex("\\s+"), " ").trim().trimEnd('-', ',', ';', ' ').ifEmpty { raw }
                val lot = (lotData?.text("RiferimentoTesto") ?: lotData?.text("RiferimentoNumero"))?.let { sure(it, src) } ?: scan.lot
                val expiry = (expData?.text("RiferimentoData")?.let(::date) ?: date(expData?.text("RiferimentoTesto")))?.let { sure(it, src) } ?: scan.expiry
                // The unit price after the line's discounts, so price = amount / quantity (what was really paid).
                val discounted = line.kids("ScontoMaggiorazione").isNotEmpty()
                val unitPrice = when {
                    qty != null && qty.signum() != 0 && (discounted || price == null) -> totalDec.divide(qty, 4, RoundingMode.HALF_UP).stripTrailingZeros()
                    else -> price
                }
                val unitRaw = line.text("UnitaMisura")
                val unit = Units.normalize(unitRaw) ?: if (qty != null && qty.stripTrailingZeros().scale() <= 0) "pz" else null
                val code = line.kids("CodiceArticolo").let { codes ->
                    // The supplier's own code first (what its documents print), a barcode otherwise.
                    (codes.firstOrNull { (it.text("CodiceTipo") ?: "").uppercase().let { t -> "EAN" !in t && "GTIN" !in t } } ?: codes.firstOrNull())?.text("CodiceValore")
                }
                items += ParsedLineItem(
                    originalDescription = description,
                    quantity = qty?.let { sure(it, src) },
                    unit = unit?.let { sure(it, src) },
                    unitPrice = unitPrice?.let { sure(it, src) },
                    lineTotalCents = sure(total, src),
                    vatRatePercent = sure(rate, src),
                    lotNumber = lot,
                    expiryDate = expiry,
                    itemCode = code,
                )
                groups[rate.stripTrailingZeros()] = (groups[rate.stripTrailingZeros()] ?: 0L) + total
                groupLines[rate.stripTrailingZeros()] = (groupLines[rate.stripTrailingZeros()] ?: 0) + 1
                text.append(listOfNotNull(code, description).joinToString("  "))
                text.append("  ").append(qty?.let { num(it) + (unitRaw?.let { u -> " $u" } ?: "") } ?: "")
                text.append(price?.let { "  x " + num(it) } ?: "")
                if (discounted) text.append(" (sconto)")
                text.append("  = ").append(money(total)).append("  IVA ").append(num(rate)).append('%')
                lot?.let { text.append("  Lotto ").append(it.value) }
                expiry?.let { text.append("  Scad. ").append(ItalianDates.format(it.value)) }
                text.append('\n')
            }
        } else {
            // Simplified invoice: amounts include VAT, no quantities.
            for (line in body.kids("DatiBeniServizi")) {
                val raw = line.text("Descrizione") ?: continue
                val amount = dec(line.text("Importo")) ?: continue
                val rate = dec(line.text("DatiIVA/Aliquota"))
                val src = "${items.size + 1}. $raw"
                items += ParsedLineItem(raw, null, null, null, sure(cents(amount), src), rate?.let { sure(it, src) }, null, null)
                text.append(raw).append("  = ").append(money(cents(amount))).append(rate?.let { "  IVA ${num(it)}%" } ?: "").append('\n')
            }
        }

        // The VAT summary: taxable amount and VAT per rate, as the invoice states them.
        var subtotal = 0L
        var vat = 0L
        val checks = mutableListOf<VatSummary.Check>()
        val summary = body.at("DatiBeniServizi")?.kids("DatiRiepilogo").orEmpty()
        if (summary.isNotEmpty()) text.append("\nRIEPILOGO IVA\n")
        for (s in summary) {
            val rate = (dec(s.text("AliquotaIVA")) ?: BigDecimal.ZERO).stripTrailingZeros()
            val taxable = cents(dec(s.text("ImponibileImporto")) ?: BigDecimal.ZERO)
            val tax = cents(dec(s.text("Imposta")) ?: BigDecimal.ZERO)
            subtotal += taxable
            vat += tax
            text.append(num(rate)).append("%").append(s.text("Natura")?.let { " ($it)" } ?: "").append("  imponibile ").append(money(taxable)).append("  imposta ").append(money(tax)).append('\n')
            groups[rate]?.let { checks += VatSummary.Check(rate, taxable, it, groupLines[rate] ?: 0) }
        }
        val total = printedTotal?.let(::cents) ?: (if (summary.isNotEmpty()) subtotal + vat else items.sumOf { it.lineTotalCents?.value ?: 0L })
        if (summary.isNotEmpty()) {
            text.append("TOTALE IMPONIBILE ").append(money(subtotal)).append('\n')
            text.append("TOTALE IVA ").append(money(vat)).append('\n')
        }
        text.append("TOTALE DOCUMENTO ").append(money(total)).append(' ').append(currency).append('\n')
        body.kids("DatiPagamento").flatMap { it.kids("DettaglioPagamento") }.forEach { p ->
            text.append("Pagamento ").append(p.text("ModalitaPagamento") ?: "").append(" ")
                .append(dec(p.text("ImportoPagamento"))?.let { money(cents(it)) } ?: "")
                .append(date(p.text("DataScadenzaPagamento"))?.let { " scadenza " + ItalianDates.format(it) } ?: "").append('\n')
        }

        val warnings = mutableSetOf<ParseWarning>()
        if (items.isEmpty()) warnings += ParseWarning.NO_ITEMS_FOUND
        if (checks.any { !it.ok }) warnings += ParseWarning.VAT_GROUP_MISMATCH
        val own = ownVat?.filter(Char::isDigit)?.takeLast(11)
        if (!own.isNullOrBlank() && buyerId != null && buyerId.filter(Char::isDigit).takeLast(11) != own) warnings += ParseWarning.OTHER_BUYER
        val src = "XML"
        val parsed = ParsedDocument(
            sellerName = seller?.let { sure(it, src) },
            documentDate = docDate?.let { sure(it, src) },
            documentNumber = number?.let { sure(it, src) },
            currency = sure(currency, src),
            subtotalCents = if (summary.isNotEmpty()) sure(subtotal, src) else null,
            vatCents = if (summary.isNotEmpty()) sure(vat, src) else null,
            totalCents = sure(total, src),
            vatBasis = sure(if (simplified) VatBasis.INCLUSIVE else VatBasis.EXCLUSIVE, src),
            lineItems = items,
            warnings = warnings,
            itemsReadBy = "e-invoice",
            vatChecks = checks,
            lotsPrinted = items.any { it.lotNumber != null },
        )
        return Read(parsed, sellerVat, buyerId, type, type in CREDIT_NOTES, text.toString().trimEnd())
    }
}
