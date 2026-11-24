-- Creator Studio 2.0, phase 1. Private owner-reviewed onboarding.
-- Verification of a creator NEVER grants distribution rights to any title.
CREATE TABLE IF NOT EXISTS creator_profiles (
 id TEXT PRIMARY KEY NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('filmmaker','studio')),
 display_name TEXT NOT NULL,
 legal_name TEXT NOT NULL,
 email TEXT NOT NULL COLLATE NOCASE UNIQUE,
 phone TEXT NOT NULL DEFAULT '',
 region TEXT NOT NULL DEFAULT '',
 portfolio_url TEXT NOT NULL DEFAULT '',
 biography TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','needs_info','verified','rejected')),
 identity_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(identity_confirmed IN (0,1)),
 authority_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(authority_confirmed IN (0,1)),
 review_note TEXT NOT NULL DEFAULT '',
 reviewed_at TEXT,
 created_at TEXT NOT NULL DEFAULT (datetime('now')),
 updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_creators_review ON creator_profiles(status,created_at DESC);
CREATE TABLE IF NOT EXISTS creator_review_events (
 id TEXT PRIMARY KEY NOT NULL,
 creator_id TEXT NOT NULL REFERENCES creator_profiles(id) ON DELETE CASCADE,
 event_type TEXT NOT NULL CHECK(event_type IN ('submitted','reviewed')),
 previous_status TEXT,
 next_status TEXT NOT NULL,
 note TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_creator_events_creator ON creator_review_events(creator_id,created_at DESC);
