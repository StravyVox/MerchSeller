package com.merchseller.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MONEY_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TEMP TABLE saved_sequences AS SELECT name, seq FROM sqlite_sequence WHERE name IN ('products', 'sales', 'sale_lines')")
        db.execSQL("""
            CREATE TABLE products_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sku TEXT NOT NULL, name TEXT NOT NULL,
                description TEXT NOT NULL, categoryId INTEGER, priceKopecks INTEGER NOT NULL,
                vatRate REAL NOT NULL, stock INTEGER NOT NULL, photoUri TEXT, updatedAt INTEGER NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO products_new
            SELECT id, sku, name, description, categoryId, 0,
                   vatRate, stock, photoUri, updatedAt FROM products
        """.trimIndent())
        db.query("SELECT id, price FROM products").use { rows ->
            while (rows.moveToNext()) {
                db.execSQL(
                    "UPDATE products_new SET priceKopecks = ? WHERE id = ?",
                    arrayOf(legacyKopecks(rows.getDouble(1)), rows.getLong(0))
                )
            }
        }
        db.execSQL("DROP TABLE products")
        db.execSQL("ALTER TABLE products_new RENAME TO products")
        db.execSQL("CREATE UNIQUE INDEX index_products_sku ON products(sku)")
        db.execSQL("CREATE INDEX index_products_categoryId ON products(categoryId)")

        // Сохраняем записанные итоги старых чеков, не пересчитывая их по новому правилу.
        db.execSQL("CREATE TEMP TABLE legacy_lines AS SELECT * FROM sale_lines")
        db.execSQL("DROP TABLE sale_lines")
        db.execSQL("""
            CREATE TABLE sales_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL,
                paymentType TEXT NOT NULL, totalKopecks INTEGER NOT NULL, note TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("INSERT INTO sales_new SELECT id, createdAt, paymentType, 0, note FROM sales")
        db.query("SELECT id, total FROM sales").use { rows ->
            while (rows.moveToNext()) {
                db.execSQL(
                    "UPDATE sales_new SET totalKopecks = ? WHERE id = ?",
                    arrayOf(legacyKopecks(rows.getDouble(1)), rows.getLong(0))
                )
            }
        }
        db.execSQL("DROP TABLE sales")
        db.execSQL("ALTER TABLE sales_new RENAME TO sales")
        db.execSQL("CREATE INDEX index_sales_createdAt ON sales(createdAt)")
        db.execSQL("""
            CREATE TABLE sale_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, saleId INTEGER NOT NULL,
                productId INTEGER NOT NULL, productName TEXT NOT NULL, sku TEXT NOT NULL,
                unitPriceKopecks INTEGER NOT NULL, vatRate REAL NOT NULL, quantity INTEGER NOT NULL,
                lineTotalKopecks INTEGER NOT NULL,
                FOREIGN KEY(saleId) REFERENCES sales(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO sale_lines
            SELECT id, saleId, productId, productName, sku, 0, vatRate, quantity, 0
            FROM legacy_lines
        """.trimIndent())
        db.query("SELECT id, unitPrice, vatRate, quantity FROM legacy_lines").use { rows ->
            while (rows.moveToNext()) {
                val price = rows.getDouble(1)
                val total = legacyLineKopecks(price, rows.getDouble(2), rows.getInt(3))
                db.execSQL(
                    "UPDATE sale_lines SET unitPriceKopecks = ?, lineTotalKopecks = ? WHERE id = ?",
                    arrayOf(legacyKopecks(price), total, rows.getLong(0))
                )
            }
        }
        db.execSQL("CREATE INDEX index_sale_lines_saleId ON sale_lines(saleId)")
        db.execSQL("CREATE INDEX index_sale_lines_productId ON sale_lines(productId)")
        db.execSQL("INSERT INTO sqlite_sequence(name, seq) SELECT name, seq FROM saved_sequences WHERE name NOT IN (SELECT name FROM sqlite_sequence)")
        db.execSQL("""
            UPDATE sqlite_sequence SET seq = MAX(seq, (SELECT seq FROM saved_sequences WHERE saved_sequences.name = sqlite_sequence.name))
            WHERE name IN (SELECT name FROM saved_sequences)
        """.trimIndent())
        db.execSQL("DROP TABLE legacy_lines")
        db.execSQL("DROP TABLE saved_sequences")
    }
}
