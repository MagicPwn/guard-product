CREATE TABLE IF NOT EXISTS quiz_upstream_sources (
    parent_id TEXT PRIMARY KEY REFERENCES parents(id),
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    public_key TEXT NOT NULL,
    key_id TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    interval_minutes INTEGER NOT NULL DEFAULT 60,
    etag TEXT,
    last_modified TEXT,
    last_checked_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT NOT NULL DEFAULT '',
    last_status TEXT NOT NULL DEFAULT 'never',
    last_version BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quiz_upstream_due
    ON quiz_upstream_sources(enabled, last_checked_at);

CREATE TABLE IF NOT EXISTS quiz_upstream_events (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES parents(id),
    event TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quiz_upstream_events_parent_time
    ON quiz_upstream_events(parent_id, created_at DESC);
