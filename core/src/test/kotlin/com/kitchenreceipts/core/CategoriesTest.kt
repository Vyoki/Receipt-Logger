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

    /** Names as a cash & carry prints them: abbreviations, brand and pack after the product. */
    @Test fun supplierAbbreviations() {
        mapOf(
            // All biscuits together, however they are written.
            "BISCOTTI FROLLINI 800 - ESEMPIO" to Category.BAKERY,
            "FETTE BISC.ESEMPIO GR.250" to Category.BAKERY,
            "BISC.RITORNELLI 700- ESEMPIO" to Category.BAKERY,
            "BISC.M.B. BISCOTTONE GR.700" to Category.BAKERY,
            // The product comes first: potatoes in sacks, not bags.
            "PAT.SACCHI KG4X5 CRT" to Category.FRUIT_VEG,
            "CIP.DORATA KG10 PZ CRT" to Category.FRUIT_VEG,
            "CIP,ROSSA KG1X16 CRT" to Category.FRUIT_VEG,
            "LIM.RET.750X16 IMP CRT" to Category.FRUIT_VEG,
            "INS.GENTILINA" to Category.FRUIT_VEG,
            "RAD.ROSSO LUNGO" to Category.FRUIT_VEG,
            "ZUCCHE VIOLINA" to Category.FRUIT_VEG,
            "PARM.REG.DOP 15M S/V 800" to Category.DAIRY_EGGS,
            "FILONE SUINO SV" to Category.MEAT,
            "CINGHIALE POLPA EXTRA" to Category.MEAT,
            "FILETTI DI TONNO ALL'OLIO" to Category.FISH,
            "PIATTI MANO LIMONE LT.5" to Category.CLEANING,
            "PIATTI FONDI PLASTICA X50" to Category.DISPOSABLES,
            "SP/SCIAMP.LAV.POLV.92 MIS." to Category.CLEANING,
            "ALCOOL ETILICO LT1" to Category.CLEANING,
            "CARBONE VEGETALE KG.10" to Category.DISPOSABLES,
            "BICARBONATO 500" to Category.DRY_GOODS,
            "POMODORO DOPPIO CONCENTRATO GR. 880" to Category.DRY_GOODS,
            // "CAR." could be meat, paper, carrots or artichokes: not guessed.
            "CAR.ESEMPIO 500" to Category.OTHER,
        ).forEach { (name, c) -> assertEquals(name, c, Categories.guess(name)) }
    }

    @Test fun oldGuessesAreRecognised() {
        // What earlier versions stored for these names: they may be guessed again; a different value was chosen.
        assertEquals(true, Categories.wasGuessedByOldRules("BISC.RITORNELLI 700", Category.OTHER.key))
        assertEquals(false, Categories.wasGuessedByOldRules("BISC.RITORNELLI 700", Category.BAKERY.key))
        assertEquals(false, Categories.wasGuessedByOldRules("BISC.RITORNELLI 700", null))
    }
}
