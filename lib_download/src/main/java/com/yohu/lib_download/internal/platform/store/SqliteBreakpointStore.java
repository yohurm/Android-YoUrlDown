package com.yohu.lib_download.internal.platform.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yohu.lib_download.YoDownload;
import com.yohu.lib_download.model.BlockInfo;
import com.yohu.lib_download.model.BreakpointInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author tingfeng.yang
 * @date 2026-06-24
 * @description 基于 SQLite 的断点存储实现。
 */
public final class SqliteBreakpointStore implements BreakpointStore {

    private final BreakpointSQLiteHelper helper;

    public SqliteBreakpointStore(@NonNull Context context) {
        this.helper = new BreakpointSQLiteHelper(context.getApplicationContext());
    }

    @Override
    public synchronized void onInit() {
        ContentValues values = new ContentValues();
        values.put("state", YoDownload.State.PAUSED.name());
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(BreakpointSQLiteHelper.TABLE_TASK, values,
                "state=?", new String[]{YoDownload.State.RUNNING.name()});
    }

    @Override
    public synchronized void close() {
        helper.close();
    }

    @Override
    public synchronized void upsert(@NonNull BreakpointInfo info) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict(BreakpointSQLiteHelper.TABLE_TASK, null,
                    taskValues(info), SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(BreakpointSQLiteHelper.TABLE_BLOCK, "task_id=?",
                    new String[]{info.taskId()});
            for (BlockInfo block : info.blocks()) {
                db.insertWithOnConflict(BreakpointSQLiteHelper.TABLE_BLOCK, null,
                        blockValues(info.taskId(), block), SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public synchronized void updateState(@NonNull String taskId, @NonNull YoDownload.State state) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(BreakpointSQLiteHelper.TABLE_TASK, values,
                "task_id=?", new String[]{taskId});
    }

    @Override
    public synchronized void updateMeta(@NonNull BreakpointInfo info) {
        helper.getWritableDatabase().update(BreakpointSQLiteHelper.TABLE_TASK, taskValues(info),
                "task_id=?", new String[]{info.taskId()});
    }

    @Override
    public synchronized void updateBlockProgress(@NonNull String taskId,
                                                 int blockIndex,
                                                 long currentOffset) {
        ContentValues values = new ContentValues();
        values.put("current_offset", currentOffset);
        helper.getWritableDatabase().update(BreakpointSQLiteHelper.TABLE_BLOCK, values,
                "task_id=? AND block_index=?",
                new String[]{taskId, String.valueOf(blockIndex)});
    }

    @Nullable
    @Override
    public synchronized BreakpointInfo query(@NonNull String taskId) {
        List<BreakpointInfo> list = queryList("task_id=?", new String[]{taskId});
        return list.isEmpty() ? null : list.get(0);
    }

    @NonNull
    @Override
    public synchronized List<BreakpointInfo> listByState(@NonNull YoDownload.State state) {
        return queryList("state=?", new String[]{state.name()});
    }

    @NonNull
    @Override
    public synchronized List<BreakpointInfo> listAll() {
        return queryList(null, null);
    }

    @Override
    public synchronized void remove(@NonNull String taskId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(BreakpointSQLiteHelper.TABLE_BLOCK, "task_id=?", new String[]{taskId});
            db.delete(BreakpointSQLiteHelper.TABLE_TASK, "task_id=?", new String[]{taskId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @NonNull
    private List<BreakpointInfo> queryList(@Nullable String selection,
                                           @Nullable String[] selectionArgs) {
        List<BreakpointInfo> result = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(BreakpointSQLiteHelper.TABLE_TASK,
                null, selection, selectionArgs, null, null, "updated_at DESC");
        try {
            while (cursor.moveToNext()) {
                BreakpointInfo info = readTask(cursor);
                info.replaceBlocks(queryBlocks(info.taskId()));
                result.add(info);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    @NonNull
    private List<BlockInfo> queryBlocks(@NonNull String taskId) {
        List<BlockInfo> result = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(BreakpointSQLiteHelper.TABLE_BLOCK,
                null, "task_id=?", new String[]{taskId}, null, null, "block_index ASC");
        try {
            while (cursor.moveToNext()) {
                result.add(new BlockInfo(
                        cursor.getInt(cursor.getColumnIndexOrThrow("block_index")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("start_offset")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("end_offset")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("current_offset"))));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    @NonNull
    private BreakpointInfo readTask(@NonNull Cursor cursor) {
        return new BreakpointInfo(
                cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("url")),
                new File(cursor.getString(cursor.getColumnIndexOrThrow("target_path"))),
                new File(cursor.getString(cursor.getColumnIndexOrThrow("temp_path"))),
                cursor.getLong(cursor.getColumnIndexOrThrow("total_length")),
                cursor.getString(cursor.getColumnIndexOrThrow("etag")),
                cursor.getString(cursor.getColumnIndexOrThrow("last_modified")),
                YoDownload.State.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                headersFromJson(cursor.getString(cursor.getColumnIndexOrThrow("headers"))),
                cursor.isNull(cursor.getColumnIndexOrThrow("block_count"))
                        ? 1
                        : cursor.getInt(cursor.getColumnIndexOrThrow("block_count")),
                parseNetworkType(cursor.getString(
                        cursor.getColumnIndexOrThrow("network_type"))),
                parsePriority(cursor.getString(cursor.getColumnIndexOrThrow("priority"))),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("mime_type")));
    }

    @NonNull
    private ContentValues taskValues(@NonNull BreakpointInfo info) {
        ContentValues values = new ContentValues();
        values.put("task_id", info.taskId());
        values.put("url", info.url());
        values.put("target_path", info.targetFile().getAbsolutePath());
        values.put("temp_path", info.tempFile().getAbsolutePath());
        values.put("total_length", info.totalLength());
        values.put("etag", info.etag());
        values.put("last_modified", info.lastModified());
        values.put("state", info.state().name());
        values.put("created_at", info.createdAt());
        values.put("updated_at", System.currentTimeMillis());
        values.put("headers", headersToJson(info.headers()));
        values.put("block_count", info.blockCount());
        values.put("network_type", info.networkType().name());
        values.put("priority", info.priority().name());
        values.put("title", info.title());
        values.put("mime_type", info.mimeType());
        return values;
    }

    @NonNull
    private static String headersToJson(@NonNull Map<String, String> headers) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (JSONException ignored) {
            }
        }
        return json.toString();
    }

    @NonNull
    private static Map<String, String> headersFromJson(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>();
        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, json.optString(key));
            }
        } catch (JSONException ignored) {
        }
        return headers;
    }

    @NonNull
    private static YoDownload.NetworkType parseNetworkType(@Nullable String raw) {
        if (raw == null) {
            return YoDownload.NetworkType.ALL;
        }
        try {
            return YoDownload.NetworkType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return YoDownload.NetworkType.ALL;
        }
    }

    @NonNull
    private static YoDownload.Priority parsePriority(@Nullable String raw) {
        if (raw == null) {
            return YoDownload.Priority.NORMAL;
        }
        try {
            return YoDownload.Priority.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return YoDownload.Priority.NORMAL;
        }
    }

    @NonNull
    private ContentValues blockValues(@NonNull String taskId, @NonNull BlockInfo block) {
        ContentValues values = new ContentValues();
        values.put("task_id", taskId);
        values.put("block_index", block.blockIndex());
        values.put("start_offset", block.startOffset());
        values.put("end_offset", block.endOffset());
        values.put("current_offset", block.currentOffset());
        return values;
    }
}
