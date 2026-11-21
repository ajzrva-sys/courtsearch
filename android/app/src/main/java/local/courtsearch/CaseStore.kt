package local.courtsearch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Saved cases and every finished name search. Only a search from today's court day is reused. */
class CaseStore(context: Context) : SQLiteOpenHelper(context, "cases.db", null, 2) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            create table cases (
                fips text not null,
                case_number text not null,
                record_json text not null,
                detail_json text not null default '',
                cached_at integer not null,
                primary key (fips, case_number)
            )
            """.trimIndent(),
        )
        db.execSQL(SEARCHES_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(SEARCHES_SQL.replace("create table searches", "create table searches_v2"))
            db.rawQuery("select name_key, cached_at, payload from searches", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val parts = key.split("|")
                    val last = parts.getOrElse(0) { "" }
                    val first = parts.getOrElse(1) { "" }
                    val middle = parts.getOrElse(2) { "" }
                    val payload = cursor.getString(2)
                    db.execSQL(
                        """
                        insert into searches_v2 (
                            name_key, last_name, first_name, middle_initial, display_name, cached_at, payload
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            key, last, first, middle,
                            displayFromPayload(payload, first, middle, last),
                            cursor.getLong(1), payload,
                        ),
                    )
                }
            }
            db.execSQL("drop table searches")
            db.execSQL("alter table searches_v2 rename to searches")
        }
    }

    fun storeCases(cases: List<CaseRecord>) {
        if (cases.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (case in cases) {
                    val record = json.encodeToString(case.copy(detail = null, detailError = ""))
                    val updated = db.compileStatement(
                        "update cases set record_json = ?, cached_at = ? where fips = ? and case_number = ?",
                    ).use { statement ->
                        statement.bindString(1, record)
                        statement.bindLong(2, now)
                        statement.bindString(3, case.fips)
                        statement.bindString(4, case.caseNumber)
                        statement.executeUpdateDelete()
                    }
                    if (updated == 0) {
                        db.execSQL(
                            "insert into cases (fips, case_number, record_json, cached_at) values (?, ?, ?, ?)",
                            arrayOf(case.fips, case.caseNumber, record, now),
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getCase(fips: String, caseNumber: String): CaseRecord? {
        synchronized(this) {
            readableDatabase.rawQuery(
                "select record_json, detail_json from cases where fips = ? and case_number = ?",
                arrayOf(fips, caseNumber),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val record = json.decodeFromString<CaseRecord>(cursor.getString(0))
                val detailText = cursor.getString(1).orEmpty()
                val detail = if (detailText.isBlank()) null else json.decodeFromString<CaseDetail>(detailText)
                return record.copy(detail = detail)
            }
        }
    }

    fun storeDetail(fips: String, caseNumber: String, detail: CaseDetail) {
        val payload = json.encodeToString(detail)
        synchronized(this) {
            writableDatabase.compileStatement(
                "update cases set detail_json = ? where fips = ? and case_number = ?",
            ).use { statement ->
                statement.bindString(1, payload)
                statement.bindString(2, fips)
                statement.bindString(3, caseNumber)
                statement.executeUpdateDelete()
            }
        }
    }

    fun storeSearch(last: String, first: String, middle: String, results: SearchResults, cachedAtMillis: Long) {
        val payload = json.encodeToString(results.copy(cachedAt = cachedAtMillis / 1000.0, fromCache = false))
        synchronized(this) {
            writableDatabase.execSQL(
                """
                insert into searches (
                    name_key, last_name, first_name, middle_initial, display_name, cached_at, payload
                ) values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    searchKey(last, first, middle), last, first, middle,
                    savedDisplayName(first, middle, last), cachedAtMillis, payload,
                ),
            )
        }
    }

    /** The newest save for this name, and only when it was run on today's Virginia court day. */
    fun getSearch(last: String, first: String, middle: String, nowMillis: Long = System.currentTimeMillis()): SearchResults? {
        synchronized(this) {
            readableDatabase.rawQuery(
                "select cached_at, payload from searches where name_key = ? order by cached_at desc limit 1",
                arrayOf(searchKey(last, first, middle)),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val cachedAt = cursor.getLong(0)
                if (!savedOnCourtDay(cachedAt, nowMillis)) return null
                val results = json.decodeFromString<SearchResults>(cursor.getString(1))
                return results.copy(fromCache = true, cachedAt = cachedAt / 1000.0)
            }
        }
    }

    fun listSearches(): List<SavedSearch> {
        synchronized(this) {
            readableDatabase.rawQuery(
                """
                select id, display_name, last_name, first_name, middle_initial, cached_at, payload
                from searches order by cached_at desc
                """.trimIndent(),
                null,
            ).use { cursor ->
                val rows = mutableListOf<SavedSearch>()
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(6)
                    val count = runCatching {
                        json.decodeFromString<SearchResults>(payload).summary.cases
                    }.getOrDefault(0)
                    rows.add(
                        SavedSearch(
                            id = cursor.getLong(0),
                            name = cursor.getString(1),
                            last = cursor.getString(2),
                            first = cursor.getString(3),
                            middle = cursor.getString(4),
                            cachedAtMillis = cursor.getLong(5),
                            caseCount = count,
                        ),
                    )
                }
                return rows
            }
        }
    }

    fun loadSearch(id: Long): OpenedSearch? {
        synchronized(this) {
            readableDatabase.rawQuery(
                "select last_name, first_name, middle_initial, cached_at, payload from searches where id = ?",
                arrayOf(id.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val cachedAt = cursor.getLong(3)
                val results = json.decodeFromString<SearchResults>(cursor.getString(4))
                return OpenedSearch(
                    last = cursor.getString(0),
                    first = cursor.getString(1),
                    middle = cursor.getString(2),
                    results = results.copy(fromCache = true, cachedAt = cachedAt / 1000.0),
                )
            }
        }
    }

    private fun displayFromPayload(payload: String, first: String, middle: String, last: String): String {
        val fromCase = runCatching {
            json.decodeFromString<SearchResults>(payload).cases.firstOrNull()?.searchedName
        }.getOrNull().orEmpty()
        if (fromCase.isNotBlank()) return fromCase
        return savedDisplayName(first, middle, last)
    }
}

private const val SEARCHES_SQL = """
create table searches (
    id integer primary key autoincrement,
    name_key text not null,
    last_name text not null,
    first_name text not null,
    middle_initial text not null,
    display_name text not null,
    cached_at integer not null,
    payload text not null
)
"""

data class SavedSearch(
    val id: Long,
    val name: String,
    val last: String,
    val first: String,
    val middle: String,
    val cachedAtMillis: Long,
    val caseCount: Int,
)

data class OpenedSearch(
    val last: String,
    val first: String,
    val middle: String,
    val results: SearchResults,
)
