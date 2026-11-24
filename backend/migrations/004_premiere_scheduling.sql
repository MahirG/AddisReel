-- AddisReel Creator Studio 2.0 phase 3: clock-enforced premiere windows.
-- No existing series/episode row is changed. Absence of a schedule preserves
-- the existing release rules for previously published titles.
CREATE TABLE IF NOT EXISTS premiere_schedules (
 series_id TEXT PRIMARY KEY NOT NULL REFERENCES series(id) ON DELETE CASCADE,
 premiere_at TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'scheduled' CHECK(state IN ('scheduled','paused','canceled')),
 created_at TEXT NOT NULL DEFAULT (datetime('now')),
 updated_at TEXT NOT NULL DEFAULT (datetime('now')),
 CHECK(length(premiere_at)=20 AND substr(premiere_at,20,1)='Z')
);
CREATE INDEX IF NOT EXISTS idx_premiere_schedules_upcoming ON premiere_schedules(state,premiere_at);

CREATE TABLE IF NOT EXISTS episode_release_schedules (
 series_id TEXT NOT NULL,
 episode_number INTEGER NOT NULL CHECK(episode_number>0),
 release_at TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'scheduled' CHECK(state IN ('scheduled','paused','canceled')),
 created_at TEXT NOT NULL DEFAULT (datetime('now')),
 updated_at TEXT NOT NULL DEFAULT (datetime('now')),
 PRIMARY KEY(series_id,episode_number),
 FOREIGN KEY(series_id,episode_number) REFERENCES episodes(series_id,number) ON DELETE CASCADE,
 CHECK(length(release_at)=20 AND substr(release_at,20,1)='Z')
);
CREATE INDEX IF NOT EXISTS idx_episode_release_schedules_upcoming ON episode_release_schedules(state,release_at);

CREATE TABLE IF NOT EXISTS premiere_schedule_events (
 id TEXT PRIMARY KEY NOT NULL,
 series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
 episode_number INTEGER,
 action TEXT NOT NULL CHECK(action IN ('schedule','reschedule','pause','resume','cancel')),
 previous_state TEXT,
 next_state TEXT NOT NULL CHECK(next_state IN ('scheduled','paused','canceled')),
 previous_at TEXT,
 next_at TEXT,
 reviewer_note TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_premiere_schedule_events_by_title ON premiere_schedule_events(series_id,created_at DESC);
