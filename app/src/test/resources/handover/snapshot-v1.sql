-- A hand-over snapshot in format 1 (ADR-060), written out by hand as the bridge writes it: the
-- user's tables with the schema of database version 2, the settings with their types, the counts.
-- It pins the format: the new app has to keep reading this file whatever it later writes.
CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL, url TEXT NOT NULL, username TEXT, password TEXT, output_format TEXT NOT NULL DEFAULT 'TS', user_agent TEXT, headers TEXT NOT NULL DEFAULT '{}', epg_url TEXT, refresh_interval_minutes INTEGER NOT NULL DEFAULT 720, enabled INTEGER NOT NULL DEFAULT 1, colour INTEGER NOT NULL DEFAULT 0, last_refresh_utc INTEGER NOT NULL DEFAULT 0, last_refresh_ok INTEGER NOT NULL DEFAULT 1, last_refresh_message TEXT, sort_index INTEGER NOT NULL DEFAULT 0);
CREATE TABLE channel_groups (id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, name TEXT NOT NULL, custom INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, locked INTEGER NOT NULL DEFAULT 0, sort_index INTEGER NOT NULL DEFAULT 0);
CREATE TABLE channels (id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, identity TEXT NOT NULL, stream_id TEXT, tvg_id TEXT, name TEXT NOT NULL, custom_name TEXT, logo_url TEXT, custom_logo_url TEXT, group_id INTEGER, number INTEGER NOT NULL DEFAULT 0, urls TEXT NOT NULL DEFAULT '[]', user_agent TEXT, headers TEXT NOT NULL DEFAULT '{}', license_type TEXT, license_key TEXT, catchup_mode TEXT NOT NULL DEFAULT 'NONE', catchup_source TEXT, catchup_days INTEGER NOT NULL DEFAULT 0, tvg_shift_minutes INTEGER NOT NULL DEFAULT 0, epg_offset_minutes INTEGER, video_decoder TEXT NOT NULL DEFAULT 'AUTO', audio_decoder TEXT NOT NULL DEFAULT 'AUTO', favourite INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, locked INTEGER NOT NULL DEFAULT 0, sort_index INTEGER NOT NULL DEFAULT 0, extra_attributes TEXT NOT NULL DEFAULT '{}', manual_epg_id TEXT, resolved_epg_id TEXT, match_kind TEXT NOT NULL DEFAULT 'NONE', watch_count INTEGER NOT NULL DEFAULT 0, last_watched_utc INTEGER NOT NULL DEFAULT 0);
CREATE TABLE vod (id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, stream_id TEXT NOT NULL, name TEXT NOT NULL, category_name TEXT, poster_url TEXT, plot TEXT, year TEXT, rating TEXT, duration_seconds INTEGER NOT NULL DEFAULT 0, container_extension TEXT NOT NULL DEFAULT 'mp4', series_id TEXT, season_number INTEGER, episode_number INTEGER, favourite INTEGER NOT NULL DEFAULT 0, resume_position_ms INTEGER NOT NULL DEFAULT 0, last_watched_utc INTEGER NOT NULL DEFAULT 0);
CREATE TABLE recordings (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER NOT NULL, channel_name TEXT NOT NULL, title TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, state TEXT NOT NULL, file_path TEXT, file_size_bytes INTEGER NOT NULL DEFAULT 0, message TEXT, series_key TEXT, watched INTEGER NOT NULL DEFAULT 0, resume_position_ms INTEGER NOT NULL DEFAULT 0, gap_count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE history (channel_id INTEGER PRIMARY KEY, last_watched_utc INTEGER NOT NULL, watch_count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE handover_meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE handover_prefs (key TEXT PRIMARY KEY, type TEXT NOT NULL, value TEXT);
INSERT INTO playlists (id, name, type, url, username, password, epg_url, last_refresh_utc, sort_index) VALUES (3, 'Provider', 'XTREAM', 'http://provider.example.com', 'alice', 's3cret', 'http://provider.example.com/xmltv.php', 1762600000000, 0);
INSERT INTO playlists (id, name, type, url, last_refresh_utc, sort_index) VALUES (7, 'Built-in', 'M3U_URL', 'https://il-epg.idanck.com/channels.m3u', 1762600000000, 1);
INSERT INTO channel_groups (id, playlist_id, name, hidden, sort_index) VALUES (11, 3, 'News', 0, 0), (12, 3, 'Adult', 1, 1);
INSERT INTO channels (id, playlist_id, identity, name, group_id, number, favourite, hidden, locked, sort_index, custom_name) VALUES
  (101, 3, 'x:1', 'Kan 11', 11, 11, 1, 0, 0, 2, NULL),
  (102, 3, 'x:2', 'Keshet 12', 11, 12, 1, 0, 0, 0, 'Twelve'),
  (103, 3, 'x:3', 'Hidden one', 12, 99, 0, 1, 1, 5, NULL),
  (104, 7, 'm:kan', 'Kan Educational', NULL, 23, 0, 0, 0, 1, NULL);
INSERT INTO vod (id, playlist_id, stream_id, name, favourite, resume_position_ms) VALUES (201, 3, 'v1', 'A film', 1, 61000);
INSERT INTO recordings (id, channel_id, channel_name, title, start_utc, end_utc, state, file_path) VALUES
  (301, 101, 'Kan 11', 'The news', 1762600000000, 1762603600000, 'COMPLETED', 'content://com.android.externalstorage.documents/tree/primary%3AShidur/document/primary%3AShidur%2Fnews.ts'),
  (302, 102, 'Keshet 12', 'Tomorrow', 4102444800000, 4102448400000, 'SCHEDULED', NULL);
INSERT INTO history (channel_id, last_watched_utc, watch_count) VALUES (101, 1762600000000, 14), (104, 1762500000000, 2);
INSERT INTO handover_prefs (key, type, value) VALUES
  ('last_channel', 'long', '101'),
  ('epg_offset', 'int', '-60'),
  ('subtitles_enabled', 'boolean', 'true'),
  ('subtitle_scale', 'float', '1.25'),
  ('pin_hash', 'string', 'aGFzaA=='),
  ('pin_salt', 'string', 'c2FsdA=='),
  ('sport_slots', 'string', '101,,102'),
  ('rec_folder', 'string', 'content://com.android.externalstorage.documents/tree/primary%3AShidur'),
  ('update_last_manifest', 'string', '{"versionCode":135}');
INSERT INTO handover_meta (key, value) VALUES
  ('format', '1'), ('sourceVersionCode', '135'), ('createdUtc', '1762650000000'), ('unreadableCredentials', '0'),
  ('count.playlists', '2'), ('count.groups', '2'), ('count.channels', '4'), ('count.favourites', '3'),
  ('count.history', '2'), ('count.recordings', '2'), ('count.scheduled', '1');
