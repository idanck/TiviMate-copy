package com.tivicopy.player.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Storage for playlists, channels, guide data, VOD and recordings.
 *
 * The guide table is the hot one: a 7-day guide for a thousand channels is on the order of
 * 10^5-10^6 rows (docs/07 section 7.8), so `programmes` is indexed on
 * `(epg_channel_id, start_utc)` and every read is a bounded window query — the UI never
 * loads a full day of every channel at once.
 *
 * Migrations are additive and versioned; `onUpgrade` must never drop a table that holds
 * user state (NFR-REL-05).
 */
class Database(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            db.execSQL("PRAGMA journal_mode=WAL")
            db.execSQL("PRAGMA synchronous=NORMAL")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive only. Each step is idempotent so a partially applied upgrade can be retried.
        for (target in (oldVersion + 1)..newVersion) {
            MIGRATIONS[target]?.forEach { statement ->
                runCatching { db.execSQL(statement) }
            }
        }
        // Indices and tables introduced later are created with IF NOT EXISTS, so replaying
        // the base schema fills any gap an interrupted upgrade left behind.
        SCHEMA.forEach { runCatching { db.execSQL(it) } }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destructive: a downgrade keeps the newer schema, which is a superset.
    }

    companion object {
        const val NAME = "tivicopy.db"
        const val VERSION = 1

        val SCHEMA: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                url TEXT NOT NULL,
                username TEXT,
                password TEXT,
                output_format TEXT NOT NULL DEFAULT 'TS',
                user_agent TEXT,
                headers TEXT NOT NULL DEFAULT '{}',
                epg_url TEXT,
                refresh_interval_minutes INTEGER NOT NULL DEFAULT 720,
                enabled INTEGER NOT NULL DEFAULT 1,
                colour INTEGER NOT NULL DEFAULT 0,
                last_refresh_utc INTEGER NOT NULL DEFAULT 0,
                last_refresh_ok INTEGER NOT NULL DEFAULT 1,
                last_refresh_message TEXT,
                sort_index INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS channel_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                custom INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                locked INTEGER NOT NULL DEFAULT 0,
                sort_index INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_unique ON channel_groups(playlist_id, name)",
            """
            CREATE TABLE IF NOT EXISTS channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                identity TEXT NOT NULL,
                stream_id TEXT,
                tvg_id TEXT,
                name TEXT NOT NULL,
                custom_name TEXT,
                logo_url TEXT,
                custom_logo_url TEXT,
                group_id INTEGER,
                number INTEGER NOT NULL DEFAULT 0,
                urls TEXT NOT NULL DEFAULT '[]',
                user_agent TEXT,
                headers TEXT NOT NULL DEFAULT '{}',
                license_type TEXT,
                license_key TEXT,
                catchup_mode TEXT NOT NULL DEFAULT 'NONE',
                catchup_source TEXT,
                catchup_days INTEGER NOT NULL DEFAULT 0,
                tvg_shift_minutes INTEGER NOT NULL DEFAULT 0,
                epg_offset_minutes INTEGER,
                video_decoder TEXT NOT NULL DEFAULT 'AUTO',
                audio_decoder TEXT NOT NULL DEFAULT 'AUTO',
                favourite INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                locked INTEGER NOT NULL DEFAULT 0,
                sort_index INTEGER NOT NULL DEFAULT 0,
                extra_attributes TEXT NOT NULL DEFAULT '{}',
                manual_epg_id TEXT,
                resolved_epg_id TEXT,
                match_kind TEXT NOT NULL DEFAULT 'NONE',
                watch_count INTEGER NOT NULL DEFAULT 0,
                last_watched_utc INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_channels_identity ON channels(playlist_id, identity)",
            "CREATE INDEX IF NOT EXISTS idx_channels_group ON channels(playlist_id, group_id, sort_index)",
            "CREATE INDEX IF NOT EXISTS idx_channels_epg ON channels(resolved_epg_id)",
            """
            CREATE TABLE IF NOT EXISTS epg_channels (
                epg_id TEXT PRIMARY KEY,
                display_names TEXT NOT NULL DEFAULT '[]',
                icon_url TEXT,
                source_url TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS programmes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                epg_channel_id TEXT NOT NULL,
                start_utc INTEGER NOT NULL,
                end_utc INTEGER NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                description TEXT,
                category TEXT,
                icon_url TEXT,
                season_number INTEGER,
                episode_number INTEGER,
                episode_id TEXT,
                rating TEXT,
                source_priority INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_prog_window ON programmes(epg_channel_id, start_utc, end_utc)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_prog_unique ON programmes(epg_channel_id, start_utc)",
            """
            CREATE TABLE IF NOT EXISTS vod (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                stream_id TEXT NOT NULL,
                name TEXT NOT NULL,
                category_name TEXT,
                poster_url TEXT,
                plot TEXT,
                year TEXT,
                rating TEXT,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                container_extension TEXT NOT NULL DEFAULT 'mp4',
                series_id TEXT,
                season_number INTEGER,
                episode_number INTEGER,
                favourite INTEGER NOT NULL DEFAULT 0,
                resume_position_ms INTEGER NOT NULL DEFAULT 0,
                last_watched_utc INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_vod_unique ON vod(playlist_id, stream_id)",
            "CREATE INDEX IF NOT EXISTS idx_vod_recent ON vod(last_watched_utc)",
            """
            CREATE TABLE IF NOT EXISTS recordings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                channel_id INTEGER NOT NULL,
                channel_name TEXT NOT NULL,
                title TEXT NOT NULL,
                start_utc INTEGER NOT NULL,
                end_utc INTEGER NOT NULL,
                state TEXT NOT NULL,
                file_path TEXT,
                file_size_bytes INTEGER NOT NULL DEFAULT 0,
                message TEXT,
                series_key TEXT,
                watched INTEGER NOT NULL DEFAULT 0,
                resume_position_ms INTEGER NOT NULL DEFAULT 0,
                gap_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_rec_state ON recordings(state, start_utc)",
            """
            CREATE TABLE IF NOT EXISTS history (
                channel_id INTEGER PRIMARY KEY,
                last_watched_utc INTEGER NOT NULL,
                watch_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        /** Reserved for schema version 2 and later; see [onUpgrade]. */
        val MIGRATIONS: Map<Int, List<String>> = emptyMap()
    }
}
