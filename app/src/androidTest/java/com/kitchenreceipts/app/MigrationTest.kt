package com.kitchenreceipts.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kitchenreceipts.app.data.AppDatabase
import com.kitchenreceipts.app.data.Migrations
import kotlinx.coroutines.flow.first
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
            // v8 -> v7: no agreed prices, credits, dismissed notices; documents without kind / ddt_refs / covered_by
            db.execSQL("DROP TABLE agreed_prices")
            db.execSQL("DROP TABLE credits")
            db.execSQL("DROP TABLE dismissed")
            db.execSQL(
                "CREATE TABLE `documents_v7` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seller_id` INTEGER NOT NULL, " +
                    "`document_date` INTEGER, `document_number` TEXT, `currency` TEXT, `subtotal_cents` INTEGER, `vat_cents` INTEGER, " +
                    "`total_cents` INTEGER, `vat_basis` TEXT NOT NULL, `file_path` TEXT NOT NULL, `mime_type` TEXT NOT NULL, " +
                    "`page_count` INTEGER NOT NULL, `file_sha256` TEXT NOT NULL, `ocr_text` TEXT, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`seller_id`) REFERENCES `sellers`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            )
            db.execSQL("DROP TABLE documents")
            db.execSQL("ALTER TABLE documents_v7 RENAME TO documents")
            db.execSQL("CREATE INDEX `index_documents_seller_id` ON `documents` (`seller_id`)")
            db.execSQL("CREATE INDEX `index_documents_document_date` ON `documents` (`document_date`)")
            db.execSQL("CREATE INDEX `index_documents_file_sha256` ON `documents` (`file_sha256`)")
            if (version == 7) {
                insertRows(db)
                db.execSQL("INSERT INTO products (id, name, normalized_name, created_at, category, family_id, brand, family_dismissed) VALUES (1, 'Mozzarella', 'mozzarella', 0, NULL, NULL, NULL, 0)")
                db.version = 7
                return@use
            }
            // v7 -> v6: line_items without pack_size
            db.execSQL(
                "CREATE TABLE `line_items_v6` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `document_id` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `original_description` TEXT NOT NULL, `product_id` INTEGER, `quantity` TEXT, " +
                    "`unit` TEXT, `unit_price` TEXT, `line_total_cents` INTEGER, `vat_rate` TEXT, `lot_number` TEXT, `expiry_date` INTEGER, " +
                    "`packages` TEXT, " +
                    "FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            db.execSQL("DROP TABLE line_items")
            db.execSQL("ALTER TABLE line_items_v6 RENAME TO line_items")
            db.execSQL("CREATE INDEX `index_line_items_document_id` ON `line_items` (`document_id`)")
            db.execSQL("CREATE INDEX `index_line_items_product_id` ON `line_items` (`product_id`)")
            if (version == 6) {
                insertRows(db)
                db.execSQL("INSERT INTO products (id, name, normalized_name, created_at, category, family_id, brand, family_dismissed) VALUES (1, 'Mozzarella', 'mozzarella', 0, NULL, NULL, NULL, 0)")
                db.version = 6
                return@use
            }
            // v6 -> v5: no product groups, products without family_id / brand / family_dismissed
            db.execSQL("DROP TABLE product_families")
            db.execSQL(
                "CREATE TABLE `products_v5` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`normalized_name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `category` TEXT)",
            )
            db.execSQL("DROP TABLE products")
            db.execSQL("ALTER TABLE products_v5 RENAME TO products")
            db.execSQL("CREATE UNIQUE INDEX `index_products_normalized_name` ON `products` (`normalized_name`)")
            if (version == 5) {
                insertRows(db)
                db.execSQL("INSERT INTO products (id, name, normalized_name, created_at, category) VALUES (1, 'Mozzarella', 'mozzarella', 0, NULL)")
                db.version = 5
                return@use
            }
            // v5 -> v4: line_items without packages
            db.execSQL(
                "CREATE TABLE `line_items_v4` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `document_id` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `original_description` TEXT NOT NULL, `product_id` INTEGER, `quantity` TEXT, " +
                    "`unit` TEXT, `unit_price` TEXT, `line_total_cents` INTEGER, `vat_rate` TEXT, `lot_number` TEXT, `expiry_date` INTEGER, " +
                    "FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            db.execSQL("DROP TABLE line_items")
            db.execSQL("ALTER TABLE line_items_v4 RENAME TO line_items")
            db.execSQL("CREATE INDEX `index_line_items_document_id` ON `line_items` (`document_id`)")
            db.execSQL("CREATE INDEX `index_line_items_product_id` ON `line_items` (`product_id`)")
            if (version == 4) {
                insertRows(db)
                db.execSQL("INSERT INTO products (id, name, normalized_name, created_at, category) VALUES (1, 'Mozzarella', 'mozzarella', 0, NULL)")
                db.version = 4
                return@use
            }
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
                val doc = migrated.checksDao().matchDocs().single()
                assertNull(doc.kind)
                assertNull(doc.coveredBy)
                assertEquals(0, migrated.checksDao().openCredits().first().size)
                val item = migrated.documentDao().itemsOnce(1).single().item
                assertEquals(0, java.math.BigDecimal("2.5").compareTo(item.quantity))
                assertEquals("L24-118", item.lotNumber)
                assertNull(item.packages)
                assertNull(item.packSize)
                val seller = migrated.sellerDao().allOnce().single()
                assertNull(seller.vatNumber)
                assertEquals(0, migrated.sellerDao().allAliases().size)
                val product = migrated.productDao().allOnce().single()
                assertEquals("Mozzarella", product.name)
                assertNull(product.category)
                assertNull(product.familyId)
                assertNull(product.brand)
                assertEquals(false, product.familyDismissed)
                assertEquals(0, migrated.productDao().familiesOnce().size)
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

    @Test fun migrate7To8() {
        createOldDatabase(7)
        openAndCheck()
    }

    @Test fun migrate6To7() {
        createOldDatabase(6)
        openAndCheck()
    }

    @Test fun migrate5To6() {
        createOldDatabase(5)
        openAndCheck()
    }

    @Test fun migrate4To5() {
        createOldDatabase(4)
        openAndCheck()
    }

    @Test fun migrate3To5() {
        createOldDatabase(3)
        openAndCheck()
    }

    @Test fun migrate1To5() {
        createOldDatabase(1)
        openAndCheck()
    }

    @Test fun migrate2To5() {
        createOldDatabase(2)
        openAndCheck()
    }
}
