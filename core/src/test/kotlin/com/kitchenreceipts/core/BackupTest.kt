package com.kitchenreceipts.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.random.Random

class BackupTest {

    private val pw = "cucina-prova-2026".toCharArray()
    private val rounds = 20_000 // fast for tests; the app uses Backup.ITERATIONS

    private fun make(files: Map<String, ByteArray>, manifest: Map<String, String> = mapOf("format" to "1", "db" to "7")): ByteArray =
        ByteArrayOutputStream().also { out ->
            Backup.write(out, pw, manifest, files.entries.asSequence().map { (n, b) -> Backup.Entry(n) { ByteArrayInputStream(b) } }, rounds, SecureRandom())
        }.toByteArray()

    private fun open(bytes: ByteArray, password: CharArray = pw): Pair<Map<String, String>, Map<String, ByteArray>> {
        val got = LinkedHashMap<String, ByteArray>()
        val m = Backup.read(ByteArrayInputStream(bytes), password) { name, data -> got[name] = data.readBytes() }
        return m to got
    }

    @Test fun roundTripSmallAndLargeFiles() {
        val big = Random(7).nextBytes(Backup.CHUNK * 3 + 12345) // several chunks, not a multiple
        val exact = Random(8).nextBytes(Backup.CHUNK) // exactly one chunk
        val files = linkedMapOf(
            "database/kitchen_receipts.db" to "SQLite format 3".toByteArray(),
            "documents/ab12.pdf" to big,
            "documents/cd34.jpg" to exact,
            "learning.json" to "{}".toByteArray(),
            "empty.txt" to ByteArray(0),
        )
        val (m, got) = open(make(files, mapOf("format" to "1", "note" to "a=b\nline two")))
        assertEquals("a=b\nline two", m["note"])
        assertEquals(files.keys.toList(), got.keys.toList())
        files.forEach { (k, v) -> assertArrayEquals(k, v, got[k]) }
    }

    @Test fun nothingButTheManifest() {
        val (m, got) = open(make(emptyMap()))
        assertEquals("7", m["db"])
        assertTrue(got.isEmpty())
    }

    @Test fun wrongPasswordIsSaidPlainly() {
        val b = make(mapOf("a.txt" to "x".toByteArray()))
        try { open(b, "another-password".toCharArray()); fail() } catch (_: Backup.WrongPassword) {}
    }

    @Test fun notABackup() {
        try { open("PK\u0003\u0004 a zip, not a backup".toByteArray()); fail() } catch (_: Backup.NotABackup) {}
        try { open(ByteArray(3)); fail() } catch (_: Backup.NotABackup) {}
    }

    @Test fun cutShortIsDetected() {
        val b = make(mapOf("documents/x.pdf" to Random(1).nextBytes(Backup.CHUNK * 2)))
        // Cut at every interesting place: inside the header, inside a chunk, exactly at a chunk boundary.
        val boundary = Backup.MAGIC.length + 16 + 4 + 7 + 4 + Backup.CHUNK + 16
        for (cut in listOf(Backup.MAGIC.length + 5, b.size / 2, boundary, b.size - 1)) {
            try { open(b.copyOf(cut)); fail("cut at $cut not noticed") } catch (_: Backup.Damaged) {}
        }
    }

    @Test fun changedByteIsDetected() {
        val b = make(mapOf("documents/x.pdf" to Random(2).nextBytes(Backup.CHUNK + 10)))
        val bad = b.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        try { open(bad); fail() } catch (_: Backup.Damaged) {}
    }

    @Test fun droppedChunkIsDetected() {
        val files = mapOf("documents/x.pdf" to Random(3).nextBytes(Backup.CHUNK * 3))
        val b = make(files)
        val head = Backup.MAGIC.length + 16 + 4 + 7
        val one = 4 + Backup.CHUNK + 16
        // Remove the second chunk: the third would now be read as the second.
        val cut = b.copyOfRange(0, head + one) + b.copyOfRange(head + 2 * one, b.size)
        try { open(cut); fail() } catch (_: Backup.Damaged) {}
    }

    @Test fun manifestCanStopTheRestoreBeforeAnyFile() {
        val b = make(mapOf("a.txt" to "x".toByteArray()), mapOf("db" to "99"))
        var files = 0
        try {
            Backup.read(ByteArrayInputStream(b), pw, onManifest = { if (it["db"]!!.toInt() > 7) throw IllegalStateException("newer") }) { _, _ -> files++ }
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(0, files)
    }

    @Test fun unsafeNamesAreRefused() {
        listOf("../x", "/etc/x", "a/../../b", "a\\b", "C:x", "", "a//b", "./a").forEach { assertFalse(it, Backup.safeName(it)) }
        listOf("documents/ab12.pdf", "learning.json", "readings/12.txt", "database/kitchen_receipts.db-wal").forEach { assertTrue(it, Backup.safeName(it)) }
        try { make(mapOf("../evil" to ByteArray(1))); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun shortPasswordRefused() {
        try {
            Backup.write(ByteArrayOutputStream(), "short".toCharArray(), emptyMap(), emptySequence(), rounds)
            fail()
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun propertiesKeepAnyText() {
        val m = linkedMapOf("s:own_name" to "RISTORANTE PROVA SAS", "x" to "a\\b=c\r\nd", "empty" to "")
        assertEquals(m, Backup.readProperties(Backup.writeProperties(m)))
    }
}
