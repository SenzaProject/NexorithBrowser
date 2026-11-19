package com.nexorith.browser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Single local SQLite database for bookmarks, history and downloads. */
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
        createDownloads(db)
    }

    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE downloads (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "url TEXT NOT NULL, " +
                "file_name TEXT NOT NULL, " +
                "mime_type TEXT, " +
                "status TEXT NOT NULL, " +
                "total_bytes INTEGER NOT NULL, " +
                "downloaded_bytes INTEGER NOT NULL, " +
                "accepts_ranges INTEGER, " +
                "validator TEXT, " +
                "saved_uri TEXT, " +
                "active_ms INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "referer TEXT, " +
                "user_agent TEXT, " +
                "failure_kind TEXT, " +
                "failure_code INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDownloads(db)
    }

    private companion object {
        const val VERSION = 2
    }
}
