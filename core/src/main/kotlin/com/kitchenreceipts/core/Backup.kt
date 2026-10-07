package com.kitchenreceipts.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * The backup file: everything the phone keeps (database, original photos and PDFs, what the app learned, settings)
 * in one file locked with a password the operator chooses. Restoring it on the same or another phone brings
 * everything back.
 *
 * Layout: the text [MAGIC], a header (salt, PBKDF2 rounds, nonce prefix), then a zip stream encrypted in chunks of
 * [CHUNK] bytes. Each chunk is sealed with AES-256-GCM; its nonce carries the chunk number and a "last chunk" flag
 * (the STREAM construction), so a chunk cannot be moved, dropped, or the file cut short without the restore noticing.
 * Chunks keep memory small: a backup of several hundred MB never needs to fit in the phone's memory.
 *
 * The first zip entry is [MANIFEST] (plain "key=value" lines) so a restore can say what is inside before
 * anything on the phone is touched.
 */
object Backup {

    const val MAGIC = "KITCHEN-RECEIPTS-BACKUP-1\n"
    const val MANIFEST = "manifest.properties"
    const val CHUNK = 1 shl 20
    const val ITERATIONS = 600_000
    const val MIN_PASSWORD = OfficeExport.MIN_PASSWORD
    const val EXTENSION = "krbackup"
    private const val TAG_BITS = 128
    private const val PREFIX = 7

    class NotABackup : Exception("This file is not a Kitchen Receipts backup")
    class WrongPassword : Exception("Wrong password")
    class Damaged(why: String) : Exception("The backup file is damaged or incomplete: $why")

    /** One file to put in the backup: [name] is its path relative to the app's storage ("documents/ab12.pdf"). */
    class Entry(val name: String, val open: () -> InputStream)

    /**
     * Writes a backup to [out]: the manifest first, then every entry. [out] is not closed.
     * Throws if a name is not a safe relative path (see [safeName]).
     */
    fun write(
        out: OutputStream,
        password: CharArray,
        manifest: Map<String, String>,
        entries: Sequence<Entry>,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ) {
        require(password.size >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(PREFIX).also(random::nextBytes)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(ByteBuffer.allocate(4).putInt(iterations).array())
        out.write(prefix)
        val sealed = SealingStream(out, OfficeExport.keyFor(password, salt, iterations), prefix)
        val zip = ZipOutputStream(sealed)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(writeProperties(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        val seen = HashSet<String>()
        for (e in entries) {
            require(safeName(e.name) && seen.add(e.name)) { "Bad or repeated name in backup: ${e.name}" }
            zip.putNextEntry(ZipEntry(e.name))
            e.open().use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
        zip.finish()
        sealed.finish()
        out.flush()
    }

    /**
     * Reads a backup: checks the password, gives the manifest to [onManifest] (which may throw to stop),
     * then each file to [sink] with its relative name. Returns the manifest.
     * Throws [NotABackup], [WrongPassword] or [Damaged]; nothing is given to [sink] for a file whose
     * chunk fails its check, and a file cut short is reported as [Damaged] at the end.
     */
    fun read(
        input: InputStream,
        password: CharArray,
        onManifest: (Map<String, String>) -> Unit = {},
        sink: (name: String, data: InputStream) -> Unit,
    ): Map<String, String> {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.length)
        try { data.readFully(magic) } catch (_: EOFException) { throw NotABackup() }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw NotABackup()
        val salt = ByteArray(16)
        val prefix = ByteArray(PREFIX)
        val iterations: Int
        try {
            data.readFully(salt)
            iterations = data.readInt()
            data.readFully(prefix)
        } catch (_: EOFException) { throw Damaged("header") }
        if (iterations !in 10_000..10_000_000) throw Damaged("header")
        val opening = OpeningStream(data, OfficeExport.keyFor(password, salt, iterations), prefix)
        val zip = ZipInputStream(opening)
        var manifest: Map<String, String>? = null
        try {
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (manifest == null) {
                    if (e.name != MANIFEST) throw Damaged("no manifest")
                    manifest = readProperties(zip.readBytes().toString(Charsets.UTF_8))
                    onManifest(manifest)
                    continue
                }
                if (!safeName(e.name)) throw Damaged("unsafe name ${e.name}")
                sink(e.name, NoClose(zip))
            }
            // Drain anything after the zip's end so the "last chunk" check below always runs.
            opening.skip(Long.MAX_VALUE)
        } catch (w: WrongPassword) {
            throw w
        } catch (d: Damaged) {
            throw d
        } catch (e: java.util.zip.ZipException) {
            throw Damaged(e.message ?: "zip")
        } catch (e: EOFException) {
            throw Damaged("cut short")
        }
        if (!opening.sawLast) throw Damaged("cut short")
        return manifest ?: throw Damaged("no manifest")
    }

    /** A relative path with no "..", no leading "/" or "\", and only ordinary characters. */
    fun safeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 300) return false
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\') || name.contains(':')) return false
        val parts = name.split('/')
        return parts.all { p -> p.isNotEmpty() && p != "." && p != ".." && p.all { it.isLetterOrDigit() || it in "._-" } }
    }

    fun writeProperties(map: Map<String, String>): String =
        map.entries.joinToString("\n", postfix = "\n") { (k, v) -> escape(k) + "=" + escape(v) }

    fun readProperties(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) out[unescape(line.substring(0, i))] = unescape(line.substring(i + 1))
        }
        return out
    }

    private fun escape(s: String) = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '=' -> append("\\e")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n'); 'r' -> append('\r'); 'e' -> append('='); else -> append(s[i + 1])
                }
                i += 2
            } else { append(c); i++ }
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()

    /** Encrypts what is written to it in [CHUNK]-sized sealed pieces: [length][ciphertext+tag]. */
    private class SealingStream(out: OutputStream, private val key: javax.crypto.SecretKey, private val prefix: ByteArray) :
        FilterOutputStream(out) {
        private val buf = ByteArray(CHUNK)
        private var len = 0
        private var counter = 0
        private var done = false

        override fun write(b: Int) {
            if (len == CHUNK) seal(false)
            buf[len++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, n: Int) {
            var o = off
            var left = n
            while (left > 0) {
                if (len == CHUNK) seal(false)
                val k = minOf(left, CHUNK - len)
                System.arraycopy(b, o, buf, len, k)
                len += k; o += k; left -= k
            }
        }

        private fun seal(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            c.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
            val sealed = c.doFinal(buf, 0, len)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            counter++
            len = 0
        }

        fun finish() {
            if (done) return
            seal(true)
            done = true
        }

        override fun flush() = out.flush()
        override fun close() { finish(); out.flush() } // the caller owns [out]
    }

    /** Opens what [SealingStream] wrote, one chunk at a time. */
    private class OpeningStream(private val src: DataInputStream, private val key: javax.crypto.SecretKey, private val prefix: ByteArray) :
        InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0
        var sawLast = false
            private set

        private fun next(): Boolean {
            if (sawLast) return false
            val n = try { src.readInt() } catch (_: EOFException) { throw Damaged("cut short") }
            if (n < 16 || n > CHUNK + 16) throw Damaged("bad chunk size")
            val sealed = ByteArray(n)
            try { src.readFully(sealed) } catch (_: EOFException) { throw Damaged("cut short") }
            // A chunk is either a middle one or the last; try the flag that the size suggests first.
            val guessLast = n < CHUNK + 16
            val opened = open(sealed, guessLast) ?: open(sealed, !guessLast) ?: run {
                if (counter == 0) throw WrongPassword() else throw Damaged("chunk $counter does not check out")
            }
            plain = opened.first
            sawLast = opened.second
            pos = 0
            counter++
            return true
        }

        private fun open(sealed: ByteArray, last: Boolean): Pair<ByteArray, Boolean>? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter, last)))
            c.updateAAD(MAGIC.toByteArray(Charsets.US_ASCII))
            c.doFinal(sealed) to last
        } catch (_: GeneralSecurityException) { null }

        override fun read(): Int {
            while (pos >= plain.size) if (!next()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!next()) return -1
            val k = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, k)
            pos += k
            return k
        }

        override fun skip(n: Long): Long {
            var left = n
            while (left > 0) {
                if (pos >= plain.size && !next()) break
                val k = minOf(left, (plain.size - pos).toLong()).toInt()
                pos += k; left -= k
            }
            return n - left
        }
    }

    /** Lets the sink close its stream without closing the zip. */
    private class NoClose(private val inner: InputStream) : InputStream() {
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
        override fun close() {}
    }

}
