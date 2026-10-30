import sqlite3

OLD_SCHEMA = """
CREATE TABLE tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uri TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    durationMs INTEGER NOT NULL,
    dateAdded INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_tracks_uri ON tracks(uri);
CREATE TABLE alarms (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    hour INTEGER NOT NULL,
    minute INTEGER NOT NULL,
    durationMinutes INTEGER NOT NULL,
    isEnabled INTEGER NOT NULL,
    daysOfWeek TEXT NOT NULL,
    label TEXT NOT NULL,
    trackId INTEGER,
    gain REAL NOT NULL DEFAULT 0.8
);
CREATE TABLE microsounds (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    title TEXT NOT NULL,
    uri TEXT NOT NULL,
    gain REAL NOT NULL,
    minIntervalMinutes INTEGER NOT NULL,
    maxIntervalMinutes INTEGER NOT NULL,
    isEnabled INTEGER NOT NULL
);
INSERT INTO tracks (uri, title, artist, durationMs, dateAdded)
    VALUES ('content://media/track1', 'Track 1', 'Artist', 180000, 1000);
INSERT INTO alarms (hour, minute, durationMinutes, isEnabled, daysOfWeek, label, trackId, gain)
    VALUES (7, 30, 5, 1, '1,2,3', 'Работа', 1, 0.8);
INSERT INTO microsounds (title, uri, gain, minIntervalMinutes, maxIntervalMinutes, isEnabled)
    VALUES ('Звук', 'content://media/micro', 0.8, 10, 20, 1);
"""

# Точные SQL из AppDatabaseMigrations (миграция 7 -> 8).
MIGRATION = """
CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    isSystem INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    playlistId INTEGER NOT NULL,
    trackUri TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT,
    addedAt INTEGER NOT NULL,
    FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId);
CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_trackUri
    ON playlist_tracks(playlistId, trackUri);
INSERT OR IGNORE INTO playlists (id, name, isSystem, createdAt) VALUES (1, '', 1, 0);
"""

db = sqlite3.connect(":memory:")
db.executescript(OLD_SCHEMA)
db.executescript(MIGRATION)

# 1. Старые данные не тронуты.
assert db.execute("SELECT COUNT(*) FROM tracks").fetchone()[0] == 1
assert db.execute("SELECT COUNT(*) FROM alarms").fetchone()[0] == 1
assert db.execute("SELECT COUNT(*) FROM microsounds").fetchone()[0] == 1
assert db.execute("SELECT gain FROM alarms").fetchone()[0] == 0.8
assert db.execute("SELECT trackId FROM alarms").fetchone()[0] == 1

# 2. «Избранное»: ровно одна строка id=1, isSystem=1, пустое имя, createdAt=0.
fav = db.execute("SELECT id, name, isSystem, createdAt FROM playlists").fetchall()
assert fav == [(1, '', 1, 0)], fav

# 3. Повторный INSERT OR IGNORE идемпотентен.
db.execute("INSERT OR IGNORE INTO playlists (id, name, isSystem, createdAt) VALUES (1, '', 1, 0)")
assert db.execute("SELECT COUNT(*) FROM playlists").fetchone()[0] == 1

# 4. Новый плейлист получает id 2 (id 1 занят «Избранным»).
cur = db.execute("INSERT INTO playlists (name, isSystem, createdAt) VALUES ('Утро', 0, 5000)")
assert cur.lastrowid == 2
assert cur.rowcount == 1

# 5. Дубликат трека в плейлисте игнорируется уникальным индексом.
insert_track = (
    "INSERT OR IGNORE INTO playlist_tracks (playlistId, trackUri, title, artist, addedAt) "
    "VALUES (?, ?, ?, ?, ?)"
)
assert db.execute(insert_track, (2, 'content://media/track1', 'Track 1', 'Artist', 100)).rowcount == 1
assert db.execute(insert_track, (2, 'content://media/track1', 'Track 1', 'Artist', 200)).rowcount == 0
# Тот же URI в другом плейлисте разрешён.
assert db.execute(insert_track, (1, 'content://media/track1', 'Track 1', 'Artist', 300)).rowcount == 1
assert db.execute(insert_track, (2, 'content://media/track2', 'Track 2', None, 100)).rowcount == 1
assert db.execute("SELECT COUNT(*) FROM playlist_tracks").fetchone()[0] == 3

# 6. Порядок: addedAt ASC, затем id.
rows = db.execute(
    "SELECT trackUri FROM playlist_tracks WHERE playlistId = 2 ORDER BY addedAt ASC, id ASC"
).fetchall()
assert rows == [('content://media/track1',), ('content://media/track2',)], rows

# 7. Сводка: «Избранное» первым, COUNT учитывает NULL-исполнителя.
summary = db.execute(
    """
    SELECT p.id, p.name, p.isSystem, COUNT(t.id) AS trackCount
    FROM playlists p
    LEFT JOIN playlist_tracks t ON t.playlistId = p.id
    GROUP BY p.id, p.name, p.isSystem, p.createdAt
    ORDER BY p.isSystem DESC, p.createdAt ASC, p.id ASC
    """
).fetchall()
assert summary == [(1, '', 1, 1), (2, 'Утро', 0, 2)], summary

# 8. Каскад: с включёнными FK удаление плейлиста убирает строки.
# PRAGMA foreign_keys не действует внутри транзакции — сначала фиксируем вставки.
db.commit()
db.execute("PRAGMA foreign_keys = ON")
assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 1
db.execute("DELETE FROM playlists WHERE id = 2 AND isSystem = 0")
db.commit()
assert db.execute("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = 2").fetchone()[0] == 0
assert db.execute("SELECT COUNT(*) FROM playlists").fetchone()[0] == 1
# «Избранное» не удаляется тем же запросом (isSystem = 0).
assert db.execute("DELETE FROM playlists WHERE id = 1 AND isSystem = 0").rowcount == 0

# 9. Без FK (как в приложении) родитель удаляется, поэтому строки удаляются вручную.
db.commit()
db.execute("PRAGMA foreign_keys = OFF")
assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 0
db.execute("INSERT INTO playlists (name, isSystem, createdAt) VALUES ('Вечер', 0, 6000)")
pid = db.execute("SELECT id FROM playlists WHERE name = 'Вечер'").fetchone()[0]
db.execute(insert_track, (pid, 'content://media/track3', 'Track 3', None, 100))
db.commit()
db.execute("BEGIN")
db.execute("DELETE FROM playlist_tracks WHERE playlistId = ?", (pid,))
db.execute("DELETE FROM playlists WHERE id = ? AND isSystem = 0", (pid,))
db.execute("COMMIT")
assert db.execute("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = ?", (pid,)).fetchone()[0] == 0

# 10. Миграция 8 -> 9: точные SQL из AppDatabaseMigrations.ADD_ALARM_*_SQL.
# Новая БД с той же схемой alarms, что была в v8 (до миграции 7->8 будильники не менялись).
db9 = sqlite3.connect(":memory:")
db9.executescript(OLD_SCHEMA)
MIGRATION_8_TO_9 = """
ALTER TABLE alarms ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'TRACK';
ALTER TABLE alarms ADD COLUMN playlistId INTEGER;
ALTER TABLE alarms ADD COLUMN shuffle INTEGER NOT NULL DEFAULT 0;
"""
db9.executescript(MIGRATION_8_TO_9)

# Существующий будильник сохранил id и данные, получив значения по умолчанию.
row = db9.execute(
    "SELECT id, hour, minute, durationMinutes, daysOfWeek, label, trackId, gain, "
    "sourceType, playlistId, shuffle FROM alarms"
).fetchall()
assert row == [(1, 7, 30, 5, '1,2,3', 'Работа', 1, 0.8, 'TRACK', None, 0)], row

# Новая строка без явных значений колонок тоже получает TRACK / NULL / 0.
db9.execute(
    "INSERT INTO alarms (hour, minute, durationMinutes, isEnabled, daysOfWeek, label) "
    "VALUES (8, 0, 2, 1, '', '')"
)
assert db9.execute(
    "SELECT sourceType, playlistId, shuffle FROM alarms WHERE hour = 8"
).fetchone() == ('TRACK', None, 0)

# PLAYLIST-будильник: ссылка хранится как INTEGER, внешнего ключа НЕТ.
db9.execute(
    "UPDATE alarms SET sourceType = 'PLAYLIST', playlistId = 2, shuffle = 1 WHERE hour = 8"
)
assert db9.execute("PRAGMA foreign_key_list('alarms')").fetchall() == []

# Удаление плейлиста не трогает будильник: playlistId остаётся «висячим» намеренно.
db9.execute("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
db9.execute("INSERT INTO playlists (id, name) VALUES (2, 'Утро')")
db9.execute("DELETE FROM playlists WHERE id = 2")
assert db9.execute(
    "SELECT sourceType, playlistId FROM alarms WHERE hour = 8"
).fetchone() == ('PLAYLIST', 2)

# Запрос предупреждения при удалении: playlistId -> количество будильников.
usage = db9.execute(
    "SELECT playlistId, COUNT(*) FROM alarms "
    "WHERE sourceType = 'PLAYLIST' AND playlistId IS NOT NULL GROUP BY playlistId"
).fetchall()
assert usage == [(2, 1)], usage

# NOT NULL/DEFAULT в фактической схеме совпадают с @ColumnInfo(defaultValue).
table_info = {r[1]: r for r in db9.execute("PRAGMA table_info('alarms')").fetchall()}
assert table_info['sourceType'][3] == 1, table_info['sourceType']  # notnull
assert table_info['sourceType'][4] == "'TRACK'", table_info['sourceType']  # default
assert table_info['playlistId'][3] == 0, table_info['playlistId']
assert table_info['playlistId'][4] is None, table_info['playlistId']
assert table_info['shuffle'][3] == 1, table_info['shuffle']
assert table_info['shuffle'][4] == '0', table_info['shuffle']

print("SQL migration verification: OK")
