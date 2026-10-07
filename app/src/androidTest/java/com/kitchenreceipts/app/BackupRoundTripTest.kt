package com.kitchenreceipts.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.files.StoredFile
import com.kitchenreceipts.core.Backup
import com.kitchenreceipts.core.ValidDocument
import com.kitchenreceipts.core.ValidLineItem
import com.kitchenreceipts.core.VatBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** A real backup of the app's real database and files, then a full check of it (the restore stops before replacing). */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    @Test fun backupThenCheck() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<KitchenReceiptsApp>()
        val c = app.container
        val original = File(app.filesDir, "documents/backup-test.jpg").apply { parentFile?.mkdirs(); writeBytes(kotlin.random.Random(42).nextBytes(300_000)) }
        c.repository.saveDocument(
            ValidDocument(
                sellerName = "ABC S.r.l.", date = LocalDate.of(2026, 10, 1), number = "B26 204177", currency = "EUR",
                subtotalCents = null, vatCents = null, totalCents = 1234, vatBasis = VatBasis.EXCLUSIVE,
                items = listOf(ValidLineItem("POMODORI PELATI", null, BigDecimal("3"), "kg", null, 1234, null, "L123", null)),
            ),
            StoredFile("documents/backup-test.jpg", "image/jpeg", 1, "sha-backup-test"), "ocr", null,
        )
        val target = File(app.cacheDir, "test.${Backup.EXTENSION}")
        val made = c.backup.create(Uri.fromFile(target), "password-prova".toCharArray())
        assertTrue(made.documents >= 1)
        assertTrue(target.length() > 300_000) // random bytes do not compress

        try {
            c.backup.prepare(Uri.fromFile(target), "wrong-password".toCharArray())
            fail()
        } catch (_: Backup.WrongPassword) {}

        val inside = c.backup.prepare(Uri.fromFile(target), "password-prova".toCharArray())
        assertEquals(made.documents, inside.documents)
        val staged = File(app.filesDir, "restore-staging/documents/backup-test.jpg")
        assertTrue(staged.readBytes().contentEquals(original.readBytes()))
        c.backup.discard()
        assertTrue(!File(app.filesDir, "restore-staging").exists())
        target.delete()
    }
}
