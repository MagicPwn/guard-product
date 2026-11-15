CREATE TABLE IF NOT EXISTS parent_sessions (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES parents(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_parent_sessions_parent_created
    ON parent_sessions(parent_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_parent_sessions_active
    ON parent_sessions(parent_id, expires_at);
