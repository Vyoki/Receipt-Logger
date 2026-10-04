package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** E-invoices (FatturaPA) read straight from the XML: everything sure, nothing invented. Content invented. */
class EInvoiceTest {

    private val xml = javaClass.classLoader!!.getResource("fixtures/fattura_elettronica.xml")!!.readBytes()

    @Test fun readsEveryValueFromTheXml() {
        val r = EInvoice.read(xml, ownVat = "09876543217")
        val d = r.parsed
        assertEquals("ABC S.r.l.", d.sellerName?.value)
        assertEquals("IT01234567897", r.sellerVat)
        assertEquals("12A/34567", d.documentNumber?.value)
        assertEquals(LocalDate.of(2026, 9, 23), d.documentDate?.value)
        assertEquals(5900L, d.subtotalCents?.value)
        assertEquals(478L, d.vatCents?.value)
        assertEquals(6378L, d.totalCents?.value)
        assertEquals(VatBasis.EXCLUSIVE, d.vatBasis?.value)
        assertFalse(r.creditNote)
        // The reference-only line ("Rif. DDT ...", zero) is not an item.
        assertEquals(4, d.lineItems.size)
        val pasta = d.lineItems[0]
        assertEquals("2222443", pasta.itemCode) // the supplier's code, not the barcode
        assertEquals(0, BigDecimal(12).compareTo(pasta.quantity!!.value))
        assertEquals("pz", pasta.unit?.value)
        assertEquals(0, BigDecimal("0.81").compareTo(pasta.unitPrice!!.value)) // after the 10% discount
        assertEquals(972L, pasta.lineTotalCents?.value)
        val meat = d.lineItems[1]
        assertEquals("L26-0915", meat.lotNumber?.value)
        assertEquals(LocalDate.of(2026, 10, 10), meat.expiryDate?.value)
        assertEquals("kg", meat.unit?.value)
        // A lot written in the description is taken out of the name.
        val cheese = d.lineItems[2]
        assertEquals("789431", cheese.lotNumber?.value)
        assertEquals("MOZZARELLA FIOR DI LATTE", cheese.originalDescription)
        // No quantity in the XML: none invented.
        val transport = d.lineItems[3]
        assertNull(transport.quantity)
        assertEquals(50L, transport.lineTotalCents?.value)
        assertTrue(d.lineItems.all { listOfNotNull(it.quantity, it.unitPrice, it.lineTotalCents, it.vatRatePercent).all { e -> e.confidence == Confidence.HIGH } })
        assertEquals(2, d.vatChecks.size)
        assertTrue(d.vatChecks.all { it.ok })
        assertTrue(ParseWarning.OTHER_BUYER !in d.warnings)
        assertTrue(r.text.contains("P.IVA IT01234567897"))
        assertTrue(r.text.contains("DDT: B26 204177 del 22/09/2026"))
    }

    @Test fun otherBuyerIsFlagged() {
        val d = EInvoice.read(xml, ownVat = "01234567897").parsed
        assertTrue(ParseWarning.OTHER_BUYER in d.warnings)
        assertTrue(ReviewReason.OTHER_BUYER in AutoAccept.reasons(DocumentDraft.fromParsed(d)))
    }

    @Test fun creditNote() {
        val r = EInvoice.read(String(xml).replace("TD24", "TD04").toByteArray())
        assertTrue(r.creditNote)
        assertTrue(r.text.startsWith("NOTA DI CREDITO"))
    }

    // ---- wrappings

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            content.size < 0x80 -> out.write(content.size)
            content.size < 0x100 -> { out.write(0x81); out.write(content.size) }
            content.size < 0x10000 -> { out.write(0x82); out.write(content.size shr 8); out.write(content.size and 0xFF) }
            else -> { out.write(0x83); out.write(content.size shr 16); out.write((content.size shr 8) and 0xFF); out.write(content.size and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private val oidSignedData = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)
    private val oidData = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)

    /** A signed-data envelope like a .p7m: the XML as one OCTET STRING (DER) or in 1000-byte chunks (BER, indefinite). */
    private fun p7m(content: ByteArray, chunked: Boolean): ByteArray {
        val octets = if (!chunked) der(0x04, content) else {
            val chunks = content.toList().chunked(1000).map { der(0x04, it.toByteArray()) }.fold(ByteArray(0)) { a, b -> a + b }
            byteArrayOf(0x24, 0x80.toByte()) + chunks + byteArrayOf(0, 0)
        }
        val eContent = der(0xA0, octets)
        val signedData = seq(der(0x02, byteArrayOf(1)), der(0x31, ByteArray(0)), seq(oidData, eContent), der(0x31, ByteArray(0)))
        return seq(oidSignedData, der(0xA0, signedData))
    }

    @Test fun signedP7mAndBase64() {
        for (chunked in listOf(false, true)) {
            val signed = p7m(xml, chunked)
            assertTrue(EInvoice.looksLikeEInvoice(signed))
            val inner = EInvoice.invoices(signed).single()
            assertEquals("12A/34567", EInvoice.read(inner).parsed.documentNumber?.value)
            val b64 = Base64.getMimeEncoder().encode(signed)
            assertEquals(6378L, EInvoice.read(EInvoice.invoices(b64).single()).parsed.totalCents?.value)
        }
    }

    @Test fun zipOfSeveralSkipsReceipts() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("IT01234567897_00001.xml")); z.write(xml); z.closeEntry()
            z.putNextEntry(ZipEntry("IT01234567897_00002.xml.p7m")); z.write(p7m(String(xml).replace("12A/34567", "12A/34568").toByteArray(), true)); z.closeEntry()
            z.putNextEntry(ZipEntry("IT01234567897_00001_MT_001.xml")); z.write("<?xml version=\"1.0\"?><FileMetadati><IdentificativoSdI>1</IdentificativoSdI></FileMetadati>".toByteArray()); z.closeEntry()
        }
        val all = EInvoice.invoices(out.toByteArray())
        assertEquals(listOf("12A/34567", "12A/34568"), all.map { EInvoice.read(it).parsed.documentNumber?.value })
    }

    @Test fun severalInvoicesInOneFileAreSplit() {
        val s = String(xml)
        val body = s.substring(s.indexOf("<FatturaElettronicaBody>"), s.indexOf("</FatturaElettronicaBody>") + "</FatturaElettronicaBody>".length)
        val two = s.replace(body, body + "\n" + body.replace("12A/34567", "12A/34599"))
        val parts = EInvoice.invoices(two.toByteArray())
        assertEquals(listOf("12A/34567", "12A/34599"), parts.map { EInvoice.read(it).parsed.documentNumber?.value })
        assertEquals("ABC S.r.l.", EInvoice.read(parts[1]).parsed.sellerName?.value)
    }

    @Test fun notAnInvoice() {
        assertFalse(EInvoice.looksLikeEInvoice("%PDF-1.4 ...".toByteArray()))
        assertFalse(EInvoice.looksLikeEInvoice("<html><body>hi</body></html>".toByteArray()))
        val doctype = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><p:FatturaElettronica><FatturaElettronicaHeader/>&e;</p:FatturaElettronica>"
        assertTrue(runCatching { EInvoice.read(doctype.toByteArray()) }.isFailure)
    }
}
