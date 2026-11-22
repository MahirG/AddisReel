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

-- Studio 3.2: optional local FFmpeg optimization queue.
-- AddisReel Studio 3.2 additive migration; safe for existing movies.
CREATE TABLE IF NOT EXISTS video_jobs (
  series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  episode_number INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued','processing','ready','original','failed')),
  original_bytes INTEGER NOT NULL DEFAULT 0,
  optimized_bytes INTEGER,
  attempts INTEGER NOT NULL DEFAULT 0,
  message TEXT NOT NULL DEFAULT '',
  lease_hash TEXT,
  lease_expires INTEGER,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY(series_id,episode_number),
  FOREIGN KEY(series_id,episode_number) REFERENCES episodes(series_id,number) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_video_jobs_status ON video_jobs(status,updated_at);
CREATE TABLE IF NOT EXISTS processor_access (
  id INTEGER PRIMARY KEY CHECK (id=1),
  token_hash TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  last_seen_at TEXT
);
