package com.kitchenreceipts.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kitchenreceipts.core.VatBasis
import java.math.BigDecimal
import java.time.LocalDate

class Converters {
    @TypeConverter fun dateToEpochDay(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun epochDayToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)

    // Exact decimal stored as text: "2.500" stays "2.500", never 2.4999999.
    @TypeConverter fun decimalToText(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun textToDecimal(v: String?): BigDecimal? = v?.let(::BigDecimal)

    @TypeConverter fun vatBasisToText(v: VatBasis?): String? = v?.name
    @TypeConverter fun textToVatBasis(v: String?): VatBasis? =
        v?.let { runCatching { VatBasis.valueOf(it) }.getOrDefault(VatBasis.UNKNOWN) }
}

@Database(
    entities = [
        SellerEntity::class,
        DocumentEntity::class,
        ProductEntity::class,
        LineItemEntity::class,
        ProductAliasEntity::class,
        UnitConversionEntity::class,
        SellerAliasEntity::class,
        ProductFamilyEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun sellerDao(): SellerDao
    abstract fun productDao(): ProductDao

    companion object {
        const val VERSION = 6
        const val NAME = "kitchen_receipts.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                // No fallbackToDestructiveMigration(): a missing migration must crash in testing,
                // never silently wipe a restaurant's purchase history.
                .build()
    }
}

/**
 * Schema history. Every schema change gets a new version, a Migration here, a test in
 * androidTest/MigrationTest, and the exported JSON in app/schemas/ committed to git.
 *
 * v1: sellers, documents, products, line_items, product_aliases
 * v2: + unit_conversions (user-defined unit conversions per product)
 * v3: + sellers.vat_number, sellers.header_profile, seller_aliases (supplier recognition)
 * v4: + products.category (inventory)
 * v5: + line_items.packages (colli)
 * v6: + product_families, products.family_id / brand / family_dismissed (product groups)
 */
object Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `unit_conversions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`product_id` INTEGER NOT NULL, " +
                    "`from_unit` TEXT NOT NULL, " +
                    "`to_unit` TEXT NOT NULL, " +
                    "`factor` TEXT NOT NULL, " +
                    "FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_unit_conversions_product_id_from_unit_to_unit` " +
                    "ON `unit_conversions` (`product_id`, `from_unit`, `to_unit`)",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sellers` ADD COLUMN `vat_number` TEXT")
            db.execSQL("ALTER TABLE `sellers` ADD COLUMN `header_profile` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sellers_vat_number` ON `sellers` (`vat_number`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `seller_aliases` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`alias_key` TEXT NOT NULL, " +
                    "`seller_id` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`seller_id`) REFERENCES `sellers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_seller_aliases_alias_key` ON `seller_aliases` (`alias_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seller_aliases_seller_id` ON `seller_aliases` (`seller_id`)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `products` ADD COLUMN `category` TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `line_items` ADD COLUMN `packages` TEXT")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `product_families` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`normalized_name` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_product_families_normalized_name` ON `product_families` (`normalized_name`)")
            db.execSQL("ALTER TABLE `products` ADD COLUMN `family_id` INTEGER")
            db.execSQL("ALTER TABLE `products` ADD COLUMN `brand` TEXT")
            db.execSQL("ALTER TABLE `products` ADD COLUMN `family_dismissed` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_family_id` ON `products` (`family_id`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
