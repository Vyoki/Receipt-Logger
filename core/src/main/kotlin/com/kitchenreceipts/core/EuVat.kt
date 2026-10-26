package com.kitchenreceipts.core

import java.math.BigInteger

/**
 * VAT numbers of suppliers in other EU countries, as printed on the forms an Italian business issues for them
 * ("Identificativo fiscale ai fini IVA: DE123456788"). A number counts only when its country's check digits are right,
 * the way an Italian number counts only when its check digit is (a misread digit fails them). Countries whose check
 * is not known here are never taken as valid.
 */
object EuVat {

    /** "DE 123456788", "ATU12345675": the country prefix and what follows, spaces and dots removed. */
    private val PRINTED = rx("(?<![A-Z0-9])(AT|BE|DE|FR|NL)\\s?([0-9A-Z][0-9A-Z .]{7,15})")

    /** The valid EU VAT numbers (prefix and digits, no spaces) printed in [text], in order. */
    fun find(text: String): List<String> = PRINTED.findAll(text.uppercase()).mapNotNull { m ->
        val body = m.groupValues[2].replace(" ", "").replace(".", "")
        // The longest valid number from the start: a word printed right after the number is no part of it.
        (body.length downTo 8).asSequence().map { m.groupValues[1] + body.take(it) }.firstOrNull(::isValid)
    }.distinct().toList()

    /** [vat] with its country prefix ("DE123456788"), checked by its country's rule. */
    fun isValid(vat: String): Boolean {
        val v = vat.uppercase().replace(" ", "").replace(".", "")
        if (v.length < 4) return false
        val body = v.substring(2)
        return when (v.take(2)) {
            "DE" -> germany(body)
            "AT" -> austria(body)
            "FR" -> france(body)
            "BE" -> belgium(body)
            "NL" -> netherlands(body)
            else -> false
        }
    }

    private fun digits(s: String) = s.map { it - '0' }

    /** 9 digits, the last by ISO 7064 MOD 11,10. */
    private fun germany(b: String): Boolean {
        if (!rx("[1-9]\\d{8}").matches(b)) return false
        val d = digits(b)
        var product = 10
        for (i in 0 until 8) {
            var sum = (d[i] + product) % 10
            if (sum == 0) sum = 10
            product = (2 * sum) % 11
        }
        val check = (11 - product).let { if (it == 10) 0 else it }
        return check == d[8]
    }

    /** "U" and 8 digits, the last from the digit sums of the others (every second one doubled). */
    private fun austria(b: String): Boolean {
        if (!rx("U\\d{8}").matches(b)) return false
        val d = digits(b.substring(1))
        var s = 0
        for (i in 0 until 7) s += if (i % 2 == 1) (2 * d[i]).let { it / 10 + it % 10 } else d[i]
        return (10 - (s + 4) % 10) % 10 == d[7]
    }

    /** A two-digit key and the 9-digit SIREN: key = (12 + 3 x (SIREN mod 97)) mod 97. Keys with letters are not checked. */
    private fun france(b: String): Boolean {
        if (!rx("\\d{11}").matches(b)) return false
        val siren = b.substring(2).toLong()
        return b.take(2).toInt() == ((12 + 3 * (siren % 97)) % 97).toInt()
    }

    /** 10 digits (from 0 or 1): the last two are 97 - (the first eight mod 97). */
    private fun belgium(b: String): Boolean {
        if (!rx("[01]\\d{9}").matches(b)) return false
        return 97 - b.take(8).toLong() % 97 == b.takeLast(2).toLong()
    }

    /**
     * 9 digits, "B" and 2 digits. The older numbers check by weights 9 to 2 (mod 11); the newer ones (since 2020)
     * by mod 97 over the whole number, letters as numbers (N = 23, L = 21, B = 11).
     */
    private fun netherlands(b: String): Boolean {
        if (!rx("\\d{9}B\\d{2}").matches(b)) return false
        val d = digits(b.take(9))
        val weighted = (0 until 8).sumOf { d[it] * (9 - it) } % 11
        if (weighted != 10 && weighted == d[8]) return true
        val numeric = ("NL$b").map { c -> if (c.isLetter()) (c - 'A' + 10).toString() else c.toString() }.joinToString("")
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }
}
