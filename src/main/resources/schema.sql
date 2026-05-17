CREATE TABLE IF NOT EXISTS ivr_session (
    session_id              TEXT PRIMARY KEY,
    brand_id                TEXT NOT NULL,
    caller_id               TEXT NOT NULL,
    current_level           TEXT NOT NULL DEFAULT 'NONE',
    target_level            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'COLLECTING',
    collected_tokens        TEXT,
    validated_tokens        TEXT,
    attempt_counts          TEXT,
    active_path_index       TEXT,
    cross_brand_tokens      TEXT,
    locked_until            TEXT,
    created_at              TEXT NOT NULL,
    last_activity_at        TEXT NOT NULL
);
