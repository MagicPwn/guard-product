CREATE TABLE IF NOT EXISTS help_requests (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    parent_id TEXT NOT NULL REFERENCES parents(id),
    client_request_id TEXT NOT NULL,
    target_package TEXT NOT NULL DEFAULT '',
    trigger_id TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    parent_reply TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (device_id, client_request_id)
);

CREATE INDEX IF NOT EXISTS idx_help_requests_device_time
    ON help_requests(device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_help_requests_parent_status
    ON help_requests(parent_id, status, created_at DESC);
