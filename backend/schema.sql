-- AddisReel Phase 3: only licensed and published titles can become public.
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS series (
  id TEXT PRIMARY KEY NOT NULL,
  title TEXT NOT NULL,
  genre TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  poster_key TEXT,
  status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published')),
  licensed INTEGER NOT NULL DEFAULT 0 CHECK (licensed IN (0,1)),
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS episodes (
  series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  number INTEGER NOT NULL CHECK (number > 0),
  title TEXT NOT NULL,
  media_prefix TEXT,
  duration_seconds INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published')),
  licensed INTEGER NOT NULL DEFAULT 0 CHECK (licensed IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (series_id, number)
);
CREATE INDEX IF NOT EXISTS idx_series_visibility ON series(status, licensed, sort_order);
CREATE INDEX IF NOT EXISTS idx_episodes_visibility ON episodes(series_id, status, licensed, number);
