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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds real old-version database files, fills them, then opens them with the current code.
 * Room runs the migrations and then validates every table against the entities, so a wrong
 * migration fails here instead of on the operator's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun setUp() { context.deleteDatabase(name) }
    @After fun tearDown() { context.deleteDatabase(name) }

    /** Creates the current schema with Room, then rewrites it back to [version]. */
    private fun createOldDatabase(version: Int) {
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply {
            openHelper.writableDatabase
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            // v4 -> v3: products without category
            db.execSQL(
                "CREATE TABLE `products_v3` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`normalized_name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL("DROP TABLE products")
            db.execSQL("ALTER TABLE products_v3 RENAME TO products")
            db.execSQL("CREATE UNIQUE INDEX `index_products_normalized_name` ON `products` (`normalized_name`)")
            db.execSQL("INSERT INTO products (id, name, normalized_name, created_at) VALUES (1, 'Mozzarella', 'mozzarella', 0)")
            if (version == 3) {
                insertRows(db)
                db.version = 3
                return@use
            }
            // v3 -> v2: no seller_aliases, sellers without vat_number / header_profile
            db.execSQL("DROP TABLE seller_aliases")
            db.execSQL("CREATE TABLE `sellers_v2` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalized_name` TEXT NOT NULL)")
            db.execSQL("DROP TABLE sellers")
            db.execSQL("ALTER TABLE sellers_v2 RENAME TO sellers")
            db.execSQL("CREATE UNIQUE INDEX `index_sellers_normalized_name` ON `sellers` (`normalized_name`)")
            // v2 -> v1: no unit_conversions
            if (version == 1) db.execSQL("DROP TABLE unit_conversions")

            insertRows(db)
            db.version = version
        }
    }

    private fun insertRows(db: SQLiteDatabase) {
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
    }

    private fun openAndCheck() {
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*Migrations.ALL).build()
        try {
            runBlocking {
                assertEquals(7106L, migrated.documentDao().fingerprints().single().totalCents)
                val item = migrated.documentDao().itemsOnce(1).single().item
                assertEquals(0, java.math.BigDecimal("2.5").compareTo(item.quantity))
                assertEquals("L24-118", item.lotNumber)
                val seller = migrated.sellerDao().allOnce().single()
                assertNull(seller.vatNumber)
                assertEquals(0, migrated.sellerDao().allAliases().size)
                val product = migrated.productDao().allOnce().single()
                assertEquals("Mozzarella", product.name)
                assertNull(product.category)
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

    @Test fun migrate3To4() {
        createOldDatabase(3)
        openAndCheck()
    }

    @Test fun migrate1To4() {
        createOldDatabase(1)
        openAndCheck()
    }

    @Test fun migrate2To4() {
        createOldDatabase(2)
        openAndCheck()
    }
}
