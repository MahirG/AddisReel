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
