-- Apply to the existing AddisReel D1 database before deploying job endpoints.
-- Jobs track transcoding work but do not execute FFmpeg inside Cloudflare Workers.
CREATE TABLE IF NOT EXISTS video_processing_jobs (
  id TEXT PRIMARY KEY NOT NULL,
  series_id TEXT NOT NULL,
  episode_number INTEGER NOT NULL CHECK (episode_number > 0),
  source_key TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued','processing','ready','failed')),
  progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  error_message TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (series_id,episode_number) REFERENCES episodes(series_id,number) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_video_processing_jobs_queue
  ON video_processing_jobs(status,created_at);
