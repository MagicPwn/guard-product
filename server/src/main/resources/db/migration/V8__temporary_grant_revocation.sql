ALTER TABLE temporary_grants ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE temporary_grants ADD COLUMN IF NOT EXISTS revoked_by TEXT;

CREATE INDEX IF NOT EXISTS idx_grants_revoked
    ON temporary_grants(device_id, revoked_at);