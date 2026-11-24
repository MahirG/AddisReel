-- Creator Studio Phase 2: exclusive, owner-reviewed rights registry.
-- Grandfather only titles already licensed AND publicly published at rollout;
-- never revoke existing playback merely by adding a new compliance workflow.
CREATE TABLE IF NOT EXISTS grandfathered_rights (
 series_id TEXT PRIMARY KEY NOT NULL REFERENCES series(id) ON DELETE CASCADE,
 grandfathered_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS rights_migration_meta (
 key TEXT PRIMARY KEY NOT NULL,
 applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT OR IGNORE INTO grandfathered_rights(series_id)
 SELECT id FROM series WHERE status='published' AND licensed=1
 AND NOT EXISTS(SELECT 1 FROM rights_migration_meta WHERE key='grandfather_snapshot');
INSERT OR IGNORE INTO rights_migration_meta(key) VALUES ('grandfather_snapshot');

CREATE TABLE IF NOT EXISTS title_rights (
 id TEXT PRIMARY KEY NOT NULL,
 series_id TEXT NOT NULL REFERENCES series(id) ON DELETE CASCADE,
 creator_id TEXT NOT NULL REFERENCES creator_profiles(id),
 agreement_ref TEXT NOT NULL UNIQUE,
 title_name TEXT NOT NULL,
 rights_type TEXT NOT NULL DEFAULT 'exclusive_first_premiere' CHECK(rights_type IN ('exclusive_first_premiere')),
 territory TEXT NOT NULL DEFAULT 'worldwide' CHECK(territory IN ('worldwide')),
 start_date TEXT NOT NULL,
 end_date TEXT,
 signed_on TEXT NOT NULL,
 original_unreleased INTEGER NOT NULL CHECK(original_unreleased IN (0,1)),
 evidence_key TEXT,
 evidence_sha256 TEXT,
 evidence_bytes INTEGER,
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','revoked')),
 checked_chain_of_title INTEGER NOT NULL DEFAULT 0 CHECK(checked_chain_of_title IN(0,1)),
 checked_exclusivity INTEGER NOT NULL DEFAULT 0 CHECK(checked_exclusivity IN(0,1)),
 review_note TEXT NOT NULL DEFAULT '',
 reviewed_at TEXT,
 created_at TEXT NOT NULL DEFAULT (datetime('now')),
 updated_at TEXT NOT NULL DEFAULT (datetime('now')),
 CHECK(end_date IS NULL OR end_date>=start_date)
);
CREATE INDEX IF NOT EXISTS idx_title_rights_series_status ON title_rights(series_id,status,start_date,end_date);
CREATE INDEX IF NOT EXISTS idx_title_rights_creator ON title_rights(creator_id,status);
CREATE TABLE IF NOT EXISTS title_rights_events (
 id TEXT PRIMARY KEY NOT NULL,
 rights_id TEXT NOT NULL REFERENCES title_rights(id) ON DELETE CASCADE,
 event_type TEXT NOT NULL CHECK(event_type IN ('submitted','document_uploaded','reviewed')),
 from_status TEXT,
 to_status TEXT NOT NULL,
 note TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_title_rights_events ON title_rights_events(rights_id,created_at DESC);
