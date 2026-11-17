package com.nexorith.browser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Single local SQLite database for bookmarks and history. */
class NexorithDatabase(context: Context) :
    SQLiteOpenHelper(context, "nexorith.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL, " +
                "last_visit INTEGER NOT NULL, " +
                "visit_count INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_history_last_visit ON history(last_visit)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migrations yet (version 1).
    }

    private companion object {
        const val VERSION = 1
    }
}
