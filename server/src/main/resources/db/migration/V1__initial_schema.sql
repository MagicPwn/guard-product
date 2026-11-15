CREATE TABLE IF NOT EXISTS parents (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    parent_id TEXT REFERENCES parents(id),
    pairing_code_hash TEXT,
    pairing_expires_at TIMESTAMP WITH TIME ZONE,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_devices_parent ON devices(parent_id);
CREATE INDEX IF NOT EXISTS idx_devices_pairing ON devices(pairing_code_hash);

CREATE TABLE IF NOT EXISTS policy_revisions (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    revision BIGINT NOT NULL,
    payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    key_id TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (device_id, revision)
);

CREATE TABLE IF NOT EXISTS usage_reports (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    usage_date DATE NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (device_id, usage_date)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    actor TEXT NOT NULL,
    event TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_device_time ON audit_events(device_id, created_at);

CREATE TABLE IF NOT EXISTS call_requests (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_calls_device_status ON call_requests(device_id, status);

CREATE TABLE IF NOT EXISTS device_apps (
    device_id TEXT NOT NULL REFERENCES devices(id),
    package_name TEXT NOT NULL,
    label TEXT NOT NULL,
    category INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (device_id, package_name)
);

CREATE TABLE IF NOT EXISTS temporary_grants (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    package_name TEXT NOT NULL,
    minutes INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE,
    applied_by TEXT
);

CREATE INDEX IF NOT EXISTS idx_grants_pending
    ON temporary_grants(device_id, applied_at, expires_at);

CREATE TABLE IF NOT EXISTS quiz_packs (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES parents(id),
    title TEXT NOT NULL,
    version BIGINT NOT NULL,
    key_id TEXT NOT NULL,
    public_key TEXT NOT NULL,
    envelope TEXT NOT NULL,
    question_count INTEGER NOT NULL,
    published_at TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (parent_id, version)
);

CREATE INDEX IF NOT EXISTS idx_quiz_packs_parent_active
    ON quiz_packs(parent_id, active, version DESC);

CREATE TABLE IF NOT EXISTS screenshots (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    call_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    image BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_screenshots_device_time
    ON screenshots(device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS quiz_attempt_results (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    attempt_id TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    target_package TEXT NOT NULL,
    trigger_id TEXT,
    policy_revision BIGINT NOT NULL,
    quiz_version BIGINT NOT NULL,
    question_count INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    reward_granted BOOLEAN NOT NULL,
    questions TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (device_id, attempt_id)
);

CREATE INDEX IF NOT EXISTS idx_quiz_results_device_time
    ON quiz_attempt_results(device_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS device_notices (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    kind TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_notices_device_status
    ON device_notices(device_id, status, created_at DESC);
