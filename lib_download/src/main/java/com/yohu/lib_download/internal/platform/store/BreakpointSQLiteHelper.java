package com.yohu.lib_download.internal.platform.store;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 下载断点数据库。
 */
final class BreakpointSQLiteHelper extends SQLiteOpenHelper {

    static final String DB_NAME = "yo_download.db";
    static final int DB_VERSION = 2;

    static final String TABLE_TASK = "download_task";
    static final String TABLE_BLOCK = "download_block";

    BreakpointSQLiteHelper(@Nullable Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_TASK + " ("
                + "task_id TEXT PRIMARY KEY,"
                + "url TEXT NOT NULL,"
                + "target_path TEXT NOT NULL,"
                + "temp_path TEXT NOT NULL,"
                + "total_length INTEGER,"
                + "etag TEXT,"
                + "last_modified TEXT,"
                + "state TEXT NOT NULL,"
                + "created_at INTEGER,"
                + "updated_at INTEGER,"
                + "headers TEXT,"
                + "block_count INTEGER,"
                + "network_type TEXT,"
                + "priority TEXT,"
                + "title TEXT,"
                + "mime_type TEXT"
                + ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BLOCK + " ("
                + "task_id TEXT NOT NULL,"
                + "block_index INTEGER NOT NULL,"
                + "start_offset INTEGER,"
                + "end_offset INTEGER,"
                + "current_offset INTEGER,"
                + "PRIMARY KEY(task_id, block_index)"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_download_task_state ON "
                + TABLE_TASK + "(state)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN headers TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN block_count INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN network_type TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN priority TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN title TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TASK
                    + " ADD COLUMN mime_type TEXT");
        }
    }
}
