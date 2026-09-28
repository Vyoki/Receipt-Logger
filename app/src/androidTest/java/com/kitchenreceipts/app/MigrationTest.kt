package com.kitchenreceipts.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a real version-1 database file, fills it, then opens it with the current code.
 * Room runs MIGRATION_1_2 and then validates every table against the entities, so a wrong
 * migration fails here instead of on a user's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun setUp() { context.deleteDatabase(name) }
    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun migrate1To2_keepsDataAndProducesCurrentSchema() {
        // Version 1 = the current schema without the unit_conversions table.
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
            openHelper.writableDatabase
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE unit_conversions")
            db.execSQL("INSERT INTO sellers (id, name, normalized_name) VALUES (1, 'Caseificio Valverde S.r.l.', 'caseificio valverde')")
            db.execSQL(
                "INSERT INTO documents (id, seller_id, document_date, document_number, currency, subtotal_cents, vat_cents, " +
                    "total_cents, vat_basis, file_path, mime_type, page_count, file_sha256, ocr_text, created_at, updated_at) " +
                    "VALUES (1, 1, 20161, '0145/2025', 'EUR', 6460, 646, 7106, 'EXCLUSIVE', 'documents/x.jpg', 'image/jpeg', 1, 'abc', NULL, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO line_items (id, document_id, position, original_description, product_id, quantity, unit, unit_price, " +
                    "line_total_cents, vat_rate, lot_number, expiry_date) VALUES (1, 1, 0, 'Mozzarella', NULL, '2.500', 'kg', '8.90', 2225, '10', 'L24-118', NULL)",
            )
            db.version = 1
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).build()
        try {
            runBlocking {
                val fp = migrated.documentDao().fingerprints().single()
                assertEquals(7106L, fp.totalCents)
                val item = migrated.documentDao().itemsOnce(1).single().item
                assertEquals(0, java.math.BigDecimal("2.5").compareTo(item.quantity))
                assertEquals("L24-118", item.lotNumber)
            }
            migrated.openHelper.readableDatabase.query("SELECT COUNT(*) FROM unit_conversions").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            assertEquals(AppDatabase.VERSION, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
        }
    }
}
