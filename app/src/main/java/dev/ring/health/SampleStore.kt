package dev.ring.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Every sample the app reads, kept forever; `sent = 0` rows still have to reach the server. Labels are not stored (always device=colmi_r02). */
class SampleStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "samples.db", null, 1) {

    companion object {
        // One helper per process: two helpers on one file would hold separate connections and lock each other.
        @Volatile private var i: SampleStore? = null
        fun get(ctx: Context): SampleStore = i ?: synchronized(this) { i ?: SampleStore(ctx.applicationContext).also { i = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples(name TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, sent INTEGER NOT NULL, PRIMARY KEY(name, ts)) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** New rows go in unsent; an existing row is replaced and marked unsent only by a larger value (same rule as VM dedup). */
    fun add(metrics: List<Metric>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT INTO samples(name, ts, value, sent) VALUES(?, ?, ?, 0) " +
                    "ON CONFLICT(name, ts) DO UPDATE SET value = excluded.value, sent = 0 WHERE excluded.value > value"
            )
            for (m in metrics) {
                st.bindString(1, m.name); st.bindLong(2, m.timestampMs); st.bindDouble(3, m.value)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Oldest unsent rows first, at most [limit]. */
    fun unsent(limit: Int): List<Metric> = readableDatabase.rawQuery(
        "SELECT name, ts, value FROM samples WHERE sent = 0 ORDER BY ts, name LIMIT $limit", null
    ).use { c ->
        val labels = mapOf("device" to "colmi_r02")
        buildList { while (c.moveToNext()) add(Metric(c.getString(0), labels, c.getDouble(2), c.getLong(1))) }
    }

    /** Marks pushed rows sent, unless the value grew meanwhile. */
    fun markSent(chunk: List<Metric>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in chunk) db.update(
                "samples", ContentValues().apply { put("sent", 1) },
                "name = ? AND ts = ? AND value = ?", arrayOf(m.name, m.timestampMs.toString(), m.value.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markAllUnsent() {
        writableDatabase.execSQL("UPDATE samples SET sent = 0")
    }

    /** (stored, oldest ts or 0, waiting). */
    fun stats(): Triple<Int, Long, Int> = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(MIN(ts), 0), COALESCE(SUM(sent = 0), 0) FROM samples", null
    ).use { c -> c.moveToFirst(); Triple(c.getInt(0), c.getLong(1), c.getInt(2)) }
}
