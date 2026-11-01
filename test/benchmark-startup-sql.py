"""Host SQLite diagnosis of existing Android startup queries; NOT an APK benchmark."""
import json
import sqlite3
import tempfile
from time import perf_counter
from pathlib import Path

results = []
for size in (10000, 100000, 300000, 600000, 1200000):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        db = sqlite3.connect(Path(folder) / 'synthetic.db')
        db.executescript('''CREATE TABLE playlists(source_key TEXT PRIMARY KEY,indexed INTEGER,total_count INTEGER);
            CREATE TABLE channels(source_key TEXT,url TEXT,kind INTEGER,adult INTEGER,position INTEGER,series_key TEXT,PRIMARY KEY(source_key,url));
            CREATE INDEX channel_kind_order ON channels(source_key,kind,position);
            CREATE TABLE favorites(url TEXT PRIMARY KEY);
            CREATE TABLE series_favorites(source_key TEXT,series_key TEXT,PRIMARY KEY(source_key,series_key));
            INSERT INTO playlists VALUES('fixture',1,0);''')
        db.executemany('INSERT INTO channels VALUES(?,?,?,?,?,?)',
                       (('fixture', f'https://example.invalid/{i}', i % 3, 0, i, f'series{i // 30}') for i in range(size)))
        db.execute('UPDATE playlists SET total_count=?', (size,))
        db.commit()
        queries = {
            'legacy_count': 'SELECT COUNT(*) FROM channels WHERE source_key=?',
            'legacy_unknown': 'SELECT COUNT(*) FROM channels WHERE source_key=? AND (kind=-1 OR adult=-1)',
            'ready_summary': 'SELECT indexed,total_count FROM playlists WHERE source_key=?',
        }
        row = {'rows': size, 'host_sql_only': True, 'queries': {}}
        for name, sql in queries.items():
            start = perf_counter()
            db.execute(sql, ('fixture',)).fetchall()
            elapsed = perf_counter() - start
            row['queries'][name] = {'seconds': elapsed, 'plan': [r[3] for r in db.execute('EXPLAIN QUERY PLAN ' + sql, ('fixture',))]}
        results.append(row)
        print(json.dumps(row), flush=True)
        db.close()
Path('work/android-startup-sql.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
