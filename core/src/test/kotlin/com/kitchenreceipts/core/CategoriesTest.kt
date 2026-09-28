package com.kitchenreceipts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    @Test fun guesses() {
        mapOf(
            "MOZZARELLA FIOR DI LATTE 125G" to Category.DAIRY_EGGS,
            "POMODORI PELATI GR.2550(1650)X6" to Category.DRY_GOODS,
            "Pomodori ciliegino" to Category.FRUIT_VEG,
            "ACETO DI VINO BIANCO LT. 1" to Category.DRY_GOODS,
            "Vino rosso Sangiovese" to Category.BEVERAGES,
            "CARTA FORNO 40CM X 50M" to Category.DISPOSABLES,
            "SALE MARINO GROSSO" to Category.DRY_GOODS,
            "Salame Felino" to Category.CURED_MEATS,
            "Petto di pollo" to Category.MEAT,
            "Filetti di merluzzo surgelati" to Category.FROZEN,
            "Orata fresca" to Category.FISH,
            "TORTA AL TESTO SPICCHI (PANI)" to Category.BAKERY,
            "Sgrassatore professionale 5L" to Category.CLEANING,
            "Acqua naturale 50cl" to Category.BEVERAGES,
            "Articolo 12345" to Category.OTHER,
        ).forEach { (name, c) -> assertEquals(name, c, Categories.guess(name)) }
    }
}
