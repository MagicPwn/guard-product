ALTER TABLE quiz_packs ADD COLUMN IF NOT EXISTS delta_envelope TEXT;
ALTER TABLE quiz_packs ADD COLUMN IF NOT EXISTS delta_base_version BIGINT;

CREATE TABLE IF NOT EXISTS quiz_key_transitions (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES parents(id),
    old_key_id TEXT NOT NULL,
    new_key_id TEXT NOT NULL,
    new_public_key TEXT NOT NULL,
    transition TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (parent_id, new_key_id)
);

CREATE INDEX IF NOT EXISTS idx_quiz_key_transitions_parent
    ON quiz_key_transitions(parent_id, created_at);
