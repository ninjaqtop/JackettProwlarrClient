package com.aggregatorx.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Version 4 (spec §3):
 *  - `scraping_configs` is re-keyed: PRIMARY KEY = providerId (was a random UUID
 *    `id`), so saving a provider's config is a true upsert. New columns carry the
 *    full mapped request (method, query param, hidden params, pagination rule,
 *    JSON result path + field names, classification data, verification state).
 *  - `site_analysis` gains `analysisMetadata` (site map + config JSON + evidence),
 *    `verificationStatus` and `verificationReason`, separate from the HTTP
 *    response `headers` column.
 *
 * Existing configs are carried over (one per provider) and marked unverified, so
 * the next analysis re-proves them; nothing a user saved is dropped.
 */
object DatabaseMigrations {

    /** Must match Room's generated schema for [com.aggregatorx.app.data.model.ScrapingConfig]. */
    const val CREATE_SCRAPING_CONFIGS_V4 = "CREATE TABLE IF NOT EXISTS `scraping_configs_v4` (" +
        "`providerId` TEXT NOT NULL, " +
        "`searchUrlTemplate` TEXT NOT NULL, " +
        "`resultSelector` TEXT NOT NULL, " +
        "`titleSelector` TEXT NOT NULL, " +
        "`urlSelector` TEXT NOT NULL, " +
        "`descriptionSelector` TEXT, " +
        "`thumbnailSelector` TEXT, " +
        "`dateSelector` TEXT, " +
        "`sizeSelector` TEXT, " +
        "`seedersSelector` TEXT, " +
        "`leechersSelector` TEXT, " +
        "`ratingSelector` TEXT, " +
        "`categorySelector` TEXT, " +
        "`nextPageSelector` TEXT, " +
        "`headers` TEXT NOT NULL, " +
        "`cookies` TEXT NOT NULL, " +
        "`postData` TEXT, " +
        "`encoding` TEXT NOT NULL, " +
        "`userAgent` TEXT NOT NULL, " +
        "`timeout` INTEGER NOT NULL, " +
        "`retryCount` INTEGER NOT NULL, " +
        "`retryDelay` INTEGER NOT NULL, " +
        "`rateLimitMs` INTEGER NOT NULL, " +
        "`method` TEXT NOT NULL, " +
        "`queryParam` TEXT, " +
        "`hiddenParams` TEXT NOT NULL, " +
        "`paginationRule` TEXT, " +
        "`paginationStart` INTEGER NOT NULL, " +
        "`isJson` INTEGER NOT NULL, " +
        "`jsonResultPath` TEXT, " +
        "`jsonTitleField` TEXT, " +
        "`jsonUrlField` TEXT, " +
        "`jsonThumbnailField` TEXT, " +
        "`classificationData` TEXT NOT NULL, " +
        "`verified` INTEGER NOT NULL, " +
        "`verifiedAt` INTEGER NOT NULL, " +
        "`confidence` REAL NOT NULL, " +
        "PRIMARY KEY(`providerId`))"

    private const val LEGACY_COLUMNS = "`providerId`, `searchUrlTemplate`, `resultSelector`, `titleSelector`, " +
        "`urlSelector`, `descriptionSelector`, `thumbnailSelector`, `dateSelector`, `sizeSelector`, " +
        "`seedersSelector`, `leechersSelector`, `ratingSelector`, `categorySelector`, `nextPageSelector`, " +
        "`headers`, `cookies`, `postData`, `encoding`, `userAgent`, `timeout`, `retryCount`, `retryDelay`, " +
        "`rateLimitMs`"

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_SCRAPING_CONFIGS_V4)
            // One row per provider: rowid order means the most recently inserted
            // legacy row wins the REPLACE for a provider that had duplicates.
            db.execSQL(
                "INSERT OR REPLACE INTO `scraping_configs_v4` ($LEGACY_COLUMNS, `method`, `queryParam`, " +
                    "`hiddenParams`, `paginationRule`, `paginationStart`, `isJson`, `jsonResultPath`, " +
                    "`jsonTitleField`, `jsonUrlField`, `jsonThumbnailField`, `classificationData`, " +
                    "`verified`, `verifiedAt`, `confidence`) " +
                    "SELECT $LEGACY_COLUMNS, CASE WHEN `postData` IS NULL THEN 'GET' ELSE 'POST' END, NULL, " +
                    "'{}', NULL, 1, 0, NULL, NULL, NULL, NULL, '{}', 0, 0, 0 " +
                    "FROM `scraping_configs` ORDER BY rowid ASC"
            )
            db.execSQL("DROP TABLE `scraping_configs`")
            db.execSQL("ALTER TABLE `scraping_configs_v4` RENAME TO `scraping_configs`")

            db.execSQL("ALTER TABLE `site_analysis` ADD COLUMN `analysisMetadata` TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE `site_analysis` ADD COLUMN `verificationStatus` TEXT NOT NULL DEFAULT 'NOT_RUN'")
            db.execSQL("ALTER TABLE `site_analysis` ADD COLUMN `verificationReason` TEXT")
        }
    }

    /** v5 column added to `scraping_configs` by [MIGRATION_4_5]; checked against Room's schema in CI. */
    const val ADD_RENDER_MODE_V5 = "ALTER TABLE `scraping_configs` ADD COLUMN `renderMode` TEXT NOT NULL DEFAULT 'RAW'"

    /**
     * Version 5: `scraping_configs.renderMode` records whether verification proved the
     * search over raw HTTP, only after JavaScript rendering, or via the site's form in
     * the WebView, so search repeats the proven path. Existing rows are RAW (their
     * route was proven, or will be re-proven, over raw HTTP); a JS-rendered page still
     * falls back to the WebView at search time when raw HTTP yields nothing parseable.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ADD_RENDER_MODE_V5)
        }
    }
}
