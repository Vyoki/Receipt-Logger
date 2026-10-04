package com.kitchenreceipts.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The office copy: everything the phone has recorded (suppliers, products, documents and their lines), written into
 * one web page for a computer, locked with a password the operator chooses.
 *
 * The data is compressed and encrypted with AES-256-GCM; the key comes from the password with PBKDF2-HMAC-SHA256.
 * Both are built into every browser (Web Crypto), so the page opens offline, with nothing to install and nothing sent
 * anywhere. The password is not stored: without it the file is unreadable. Photos and PDFs are not included.
 */
object OfficeExport {

    data class Seller(val id: Long, val name: String, val vat: String?)
    data class Family(val id: Long, val name: String)
    data class Product(val id: Long, val name: String, val brand: String?, val category: String, val categoryChosen: Boolean, val familyId: Long?)
    data class Line(
        val description: String,
        val productId: Long?,
        val quantity: BigDecimal?,
        val unit: String?,
        val unitPrice: BigDecimal?,
        val totalCents: Long?,
        val vatRate: BigDecimal?,
        val lot: String?,
        val expiry: LocalDate?,
        val packages: String?,
        val packSize: String?,
    )
    data class Document(
        val id: Long,
        val sellerId: Long,
        val date: LocalDate?,
        val number: String?,
        val currency: String?,
        val subtotalCents: Long?,
        val vatCents: Long?,
        val totalCents: Long?,
        val vatBasis: VatBasis,
        /** "photo", "pdf" or "e-invoice". */
        val source: String,
        val lines: List<Line>,
    )
    data class Snapshot(
        val business: String?,
        val exportedAt: LocalDateTime,
        val sellers: List<Seller>,
        val families: List<Family>,
        val products: List<Product>,
        val documents: List<Document>,
    )

    const val FORMAT = "kitchen-receipts-office"
    const val ITERATIONS = 600_000
    private const val START = "/*KR-DATA*/"
    private const val END = "/*KR-END*/"

    // ---------------------------------------------------------------- JSON

    fun json(s: Snapshot): String {
        val o = StringBuilder()
        fun str(v: String?) {
            if (v == null) { o.append("null"); return }
            o.append('"')
            for (c in v) when {
                c == '"' -> o.append("\\\"")
                c == '\\' -> o.append("\\\\")
                c == '\n' -> o.append("\\n")
                c == '\r' -> o.append("\\r")
                c == '\t' -> o.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> o.append(String.format("\\u%04x", c.code))
                // Never "</script>" inside the page, whatever a description says.
                c == '<' -> o.append("\\u003c")
                else -> o.append(c)
            }
            o.append('"')
        }
        fun num(v: Long?) { o.append(v?.toString() ?: "null") }
        // Decimals as text, exactly as stored: no binary rounding on the way.
        fun dec(v: BigDecimal?) = str(v?.stripTrailingZeros()?.toPlainString())
        fun <T> arr(items: List<T>, f: (T) -> Unit) {
            o.append('[')
            items.forEachIndexed { i, it -> if (i > 0) o.append(','); f(it) }
            o.append(']')
        }
        o.append("{\"format\":"); str(FORMAT)
        o.append(",\"schema\":1,\"business\":"); str(s.business)
        o.append(",\"exportedAt\":"); str(s.exportedAt.withNano(0).toString())
        o.append(",\"sellers\":"); arr(s.sellers) { o.append("{\"id\":"); num(it.id); o.append(",\"name\":"); str(it.name); o.append(",\"vat\":"); str(it.vat); o.append('}') }
        o.append(",\"families\":"); arr(s.families) { o.append("{\"id\":"); num(it.id); o.append(",\"name\":"); str(it.name); o.append('}') }
        o.append(",\"products\":"); arr(s.products) {
            o.append("{\"id\":"); num(it.id); o.append(",\"name\":"); str(it.name); o.append(",\"brand\":"); str(it.brand)
            o.append(",\"category\":"); str(it.category); o.append(",\"categoryChosen\":").append(it.categoryChosen)
            o.append(",\"familyId\":"); num(it.familyId); o.append('}')
        }
        o.append(",\"documents\":"); arr(s.documents) { d ->
            o.append("{\"id\":"); num(d.id); o.append(",\"sellerId\":"); num(d.sellerId); o.append(",\"date\":"); str(d.date?.toString())
            o.append(",\"number\":"); str(d.number); o.append(",\"currency\":"); str(d.currency)
            o.append(",\"subtotal\":"); num(d.subtotalCents); o.append(",\"vat\":"); num(d.vatCents); o.append(",\"total\":"); num(d.totalCents)
            o.append(",\"vatBasis\":"); str(d.vatBasis.name); o.append(",\"source\":"); str(d.source)
            o.append(",\"lines\":"); arr(d.lines) { l ->
                o.append("{\"d\":"); str(l.description); o.append(",\"p\":"); num(l.productId); o.append(",\"q\":"); dec(l.quantity)
                o.append(",\"u\":"); str(l.unit); o.append(",\"pr\":"); dec(l.unitPrice); o.append(",\"t\":"); num(l.totalCents)
                o.append(",\"v\":"); dec(l.vatRate); o.append(",\"lot\":"); str(l.lot); o.append(",\"exp\":"); str(l.expiry?.toString())
                o.append(",\"pk\":"); str(l.packages); o.append(",\"ps\":"); str(l.packSize); o.append('}')
            }
            o.append('}')
        }
        o.append('}')
        return o.toString()
    }

    // ---------------------------------------------------------------- encryption

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** The encrypted envelope (a small JSON object): format, KDF parameters, salt, IV and the ciphertext. */
    fun encrypt(plain: String, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): String {
        require(password.size >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters" }
        val zipped = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(plain.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT.toByteArray())
        val data = cipher.doFinal(zipped)
        val b64 = Base64.getEncoder()
        return "{\"format\":\"$FORMAT\",\"v\":1,\"kdf\":\"PBKDF2-SHA256\",\"iter\":$iterations,\"salt\":\"${b64.encodeToString(salt)}\"," +
            "\"iv\":\"${b64.encodeToString(iv)}\",\"zip\":\"gzip\",\"data\":\"${b64.encodeToString(data)}\"}"
    }

    /** Opens an envelope made by [encrypt] (used by tests; the office page does the same in the browser). */
    fun decrypt(envelope: String, password: CharArray): String {
        fun field(name: String) = Regex("\"$name\":\"?([^\",}]*)").find(envelope)?.groupValues?.get(1) ?: throw IllegalArgumentException("No $name")
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, b64.decode(field("salt")), field("iter").toInt()), GCMParameterSpec(128, b64.decode(field("iv"))))
        cipher.updateAAD(FORMAT.toByteArray())
        val zipped = cipher.doFinal(b64.decode(field("data")))
        return GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    const val MIN_PASSWORD = 8

    // ---------------------------------------------------------------- the page

    /** The office page: [template] (the viewer) with the encrypted envelope put in its data slot. */
    fun page(template: String, envelope: String): String {
        val a = template.indexOf(START)
        val b = template.indexOf(END)
        require(a >= 0 && b > a) { "The office page template has no data slot" }
        return template.substring(0, a + START.length) + envelope + template.substring(b)
    }
}
