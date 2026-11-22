-- One-admin account; password hashes and sessions stay in D1, never in browser storage.
CREATE TABLE IF NOT EXISTS admin_account (
 id INTEGER PRIMARY KEY CHECK(id=1),
 email TEXT NOT NULL COLLATE NOCASE UNIQUE,
 password_salt TEXT NOT NULL,
 password_hash TEXT NOT NULL,
 failed_attempts INTEGER NOT NULL DEFAULT 0,
 locked_until INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL DEFAULT (unixepoch()),
 password_updated_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE TABLE IF NOT EXISTS admin_sessions (
 token_hash TEXT PRIMARY KEY,
 account_id INTEGER NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
 expires_at INTEGER NOT NULL,
 created_at INTEGER NOT NULL DEFAULT (unixepoch()),
 last_seen_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_expiry ON admin_sessions(expires_at);
