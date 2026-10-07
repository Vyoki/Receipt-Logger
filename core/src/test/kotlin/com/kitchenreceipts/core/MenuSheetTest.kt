package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class MenuSheetTest {

    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual!!))

    @Test fun excelSheet() {
        val m = MenuSheet.read(javaClass.getResource("/fixtures/menu_esempio.xlsx")!!.readBytes())
        assertEquals(listOf("Spaghetti al pomodoro", "Tiramisù", "Coperto"), m.dishes.map { it.name })
        val s = m.dishes[0]
        assertEquals("primi", s.course)
        assertEquals(1200L, s.salePriceCents)
        assertTrue(s.priceIncludesVat)
        assertEquals(listOf("Spaghetti", "Passata di pomodoro", "Olio extravergine"), s.ingredients.map { it.name })
        eq("100", s.ingredients[0].quantity)
        assertEquals("g", s.ingredients[0].unit)
        eq("1.8", s.ingredients[0].price)
        assertEquals("kg", s.ingredients[0].priceUnit)
        eq("5", s.ingredients[2].wastePercent)
        assertEquals("l", s.ingredients[2].priceUnit)
        // A row without a quantity is left out and said.
        assertEquals(listOf("Basilico"), s.skipped)
        val t = m.dishes[1]
        assertEquals("dolci", t.course)
        eq("8", t.portions)
        assertEquals(2, t.ingredients.size)
        val c = m.dishes[2]
        assertEquals(250L, c.salePriceCents)
        assertTrue(c.ingredients.isEmpty())
    }

    @Test fun italianCsv() {
        val csv = "﻿Piatto;Portata;Prezzo menu;Ingrediente;Quantità;Unità;Scarto %;Prezzo ingrediente;Prezzo per\r\n" +
            "Tartare;Antipasti;10,00;Manzo;100;g;10;13,00;kg\r\n" +
            ";;;\"Olio, extravergine\";0,01;l;5;12;l\r\n" +
            "Crostini;Antipasti;8;Pane;0,32;kg;10%;2,25;kg\r\n"
        val m = MenuSheet.read(csv.toByteArray())
        assertEquals(2, m.dishes.size)
        assertEquals("Olio, extravergine", m.dishes[0].ingredients[1].name)
        eq("0.01", m.dishes[0].ingredients[1].quantity)
        eq("10", m.dishes[1].ingredients[0].wastePercent)
        eq("1000", BigDecimal(m.dishes[0].salePriceCents!!))
    }

    @Test fun englishHeadingsAndPercentCells() {
        val rows = listOf(
            listOf("Dish", "Course", "Price", "Includes VAT", "VAT %", "Portions", "Ingredient", "Qty", "Unit", "Waste %", "Ingredient price", "Price per"),
            listOf("Soup", "Main courses", "9", "No", "0.1", "4", "Chickpeas", "0.4", "kg", "0.05", "3", "kg"),
        ).map { r -> r.map { SheetCell(it, it.toBigDecimalOrNull()) } }
        val d = MenuSheet.parse(rows).dishes.single()
        assertEquals("secondi", d.course)
        assertEquals(false, d.priceIncludesVat)
        eq("10", d.vatRatePercent)
        eq("5", d.ingredients.single().wastePercent)
    }

    @Test fun notAMenu() {
        try { MenuSheet.read("a;b;c\n1;2;3\n".toByteArray()); fail() } catch (_: MenuSheet.NotAMenu) {}
        assertNull(MenuSheet.courseKey(""))
    }

    /** The kitchen's real sheet, when given (never committed): MENU_XLSX=/path/file.xlsx */
    @Test fun realSheetIfGiven() {
        val path = System.getenv("MENU_XLSX") ?: return
        val m = MenuSheet.read(File(path).readBytes())
        m.dishes.forEach { d -> println("${d.name} | ${d.course} | ${d.salePriceCents} | ${d.ingredients.size} ingr | skipped ${d.skipped}") }
        println("problems: ${m.problems}")
    }
}

class MenuImporterTest {
    private val products = listOf(
        ProductRef(1, "Pecorino romano DOP"), ProductRef(2, "Mozzarella fior di latte"), ProductRef(3, "Mozzarella di bufala"),
        ProductRef(4, "Olio extravergine"), ProductRef(5, "Pane"), ProductRef(6, "Panettone"),
    )

    @Test fun linksOnlyWhenOneProductIsMeant() {
        assertEquals(1L, MenuImporter.link("Pecorino", products))
        assertEquals(null, MenuImporter.link("Mozzarella", products)) // two mozzarelle: the operator chooses
        assertEquals(2L, MenuImporter.link("MOZZARELLA FIOR DI LATTE", products))
        assertEquals(5L, MenuImporter.link("Pane", products)) // the exact name, not panettone
        assertEquals(4L, MenuImporter.link("olio", products))
        assertEquals(null, MenuImporter.link("Tartufo", products))
    }

    @Test fun planKeepsTheOperatorsLinksAndListsWhatIsGone() {
        val menu = MenuImport(
            listOf(
                ImportedDish("Tartare", "antipasti", 1000, true, BigDecimal.TEN, BigDecimal.ONE, listOf(
                    ImportedIngredient("Manzo", BigDecimal("100"), "g", BigDecimal.TEN, BigDecimal("13"), "kg"),
                    ImportedIngredient("Pecorino", BigDecimal("60"), "g", BigDecimal.ZERO, BigDecimal("24"), "kg"),
                ), emptyList()),
                ImportedDish("Crostini", "antipasti", 800, true, BigDecimal.TEN, BigDecimal.ONE, emptyList(), listOf("Lardo")),
            ),
            emptyList(),
        )
        val existing = listOf(ExistingDish(7, "TARTARE", listOf("manzo" to 99L)), ExistingDish(8, "Vecchio piatto", emptyList()))
        val p = MenuImporter.plan(menu, existing, products)
        assertEquals(1, p.added)
        assertEquals(1, p.updated)
        assertEquals(7L, p.dishes[0].existingId)
        assertEquals(99L, p.dishes[0].ingredients[0].productId) // kept from the app
        assertTrue(p.dishes[0].ingredients[0].keptLink)
        assertEquals(1L, p.dishes[0].ingredients[1].productId) // linked by name
        assertEquals(listOf(8L), p.notInFile.map { it.id })
        assertEquals(1, p.skipped)
        assertEquals(1, p.withoutIngredients)
    }
}
