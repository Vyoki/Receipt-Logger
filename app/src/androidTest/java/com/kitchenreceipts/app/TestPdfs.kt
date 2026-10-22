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
    fun write(file: File, pages: Int, rotated: Set<Int> = emptySet(), userPassword: String? = null): File {
        PDDocument().use { doc ->
            for (i in 0 until pages) {
                val page = PDPage(PDRectangle.A4)
                if (i in rotated) page.rotation = 90
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    pageLines(i).forEachIndexed { row, text ->
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
}
