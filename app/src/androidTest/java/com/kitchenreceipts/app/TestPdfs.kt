package com.kitchenreceipts.app

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** Invented PDFs written with PDFBox in the tests: digital pages with a little text each. Nothing real. */
object TestPdfs {

    /** The text printed on page [i] (from 0): it says which page it is, so a part cut from a file can be checked. */
    fun pageLines(i: Int): List<String> = listOf(
        "PAGINA ${i + 1}",
        "ABC S.r.l. - P.IVA 01234567897",
        "Fattura n. ${100 + i} del 03/02/2031",
        "Patate kg 10,000 0,90 9,00",
    )

    /**
     * An A4 PDF of [pages] pages written to [file]. [rotated]: pages turned a quarter (their text runs another way);
     * [userPassword]: the file is protected and cannot be opened without it.
     */
    fun write(file: File, pages: Int, rotated: Set<Int> = emptySet(), userPassword: String? = null): File =
        writeLines(file, (0 until pages).map(::pageLines), rotated, userPassword)

    /** An A4 PDF with the given lines on each page, one line under the other at the left. */
    fun writeLines(file: File, pages: List<List<String>>, rotated: Set<Int> = emptySet(), userPassword: String? = null): File {
        PDDocument().use { doc ->
            pages.forEachIndexed { i, lines ->
                val page = PDPage(PDRectangle.A4)
                if (i in rotated) page.rotation = 90
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    lines.forEachIndexed { row, text ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 11f)
                        cs.newLineAtOffset(40f, 780f - row * 40f)
                        cs.showText(text)
                        cs.endText()
                    }
                }
            }
            if (userPassword != null) {
                doc.protect(StandardProtectionPolicy("owner-prova", userPassword, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            doc.save(file)
        }
        return file
    }

    /** A "scanned" PDF: every page only a grey picture-like block, no text at all. */
    fun writeScanned(file: File, pages: Int): File {
        PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(0.6f, 0.6f, 0.6f)
                    cs.addRect(40f, 200f, 500f, 560f)
                    cs.fill()
                }
            }
            doc.save(file)
        }
        return file
    }

    /**
     * The first page of an invented invoice in the e-invoice printout layout: the parties' boxes, the identity row and
     * (when [ends]) the "Totale documento" box. Only ABC S.r.l. and RISTORANTE PROVA SAS, dates in 2031.
     */
    fun invoicePage(number: Int, ends: Boolean = true): List<String> = buildList {
        add("Cedente/prestatore (fornitore)")
        add("ABC S.r.l.")
        add("Partita IVA: IT01234567897")
        add("Cessionario/committente (cliente)")
        add("RISTORANTE PROVA SAS")
        add("Partita IVA: IT09876543217")
        add("Tipologia documento")
        add("TD01 fattura $number/2031 del 03/02/2031")
        add("Descrizione")
        add("Patate kg 10,000 0,90 9,00")
        if (ends) {
            add("Totale documento")
            add("9,90")
        }
    }

    /** A page that carries on the invoice before: more lines and its end. */
    fun lastPage(): List<String> = listOf("Descrizione", "Cipolle kg 5,000 1,00 5,00", "Totale documento", "14,90")
}
