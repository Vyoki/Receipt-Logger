package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPagesTest {
    private val five = javaClass.classLoader!!.getResource("fixtures/fattura_cash_and_carry_5_pagine.txt")!!.readText()
    private val pages = five.split(ReceiptParser.PAGE_BREAK).map { ReceiptParser.parse(it) }
    private val whole = ReceiptParser.parse(five)

    @Test fun onlyPagesWithProblemsAreRead() {
        // Everything checks out: nothing to pin down, every page would be read only if asked.
        assertEquals(pages.indices.toList(), AiReader.pagesToRead(pages, whole))
        // A misread amount on page 2 only.
        val broken = pages.toMutableList()
        val p = broken[1]
        broken[1] = p.copy(lineItems = p.lineItems.mapIndexed { i, it -> if (i == 0) it.copy(warnings = setOf(ParseWarning.LINE_TOTAL_MISMATCH)) else it })
        assertEquals(listOf(1), AiReader.pagesToRead(broken, whole))
        // Missing date too: page 1 is added for the header.
        assertEquals(listOf(0, 1), AiReader.pagesToRead(broken, whole.copy(documentDate = null)))
        // Missing total: the last page.
        assertEquals(listOf(1, 4), AiReader.pagesToRead(broken, whole.copy(totalCents = null)))
    }

    @Test fun combineKeepsTheBetterPage() {
        val aiPage = pages[1].copy(lineItems = pages[1].lineItems.map { it.copy(quantity = it.quantity?.copy(confidence = Confidence.LOW)) })
        val combined = AiReader.combineItems(pages, listOf(null, aiPage, null, null, null))
        assertEquals(pages.flatMap { it.lineItems }, combined) // the AI's page is worse: the regular lines stay
    }
}
