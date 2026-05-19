CREATE TABLE IF NOT EXISTS ivr_session (
    session_id              TEXT PRIMARY KEY,
    brand_id                TEXT NOT NULL,
    caller_id               TEXT NOT NULL,
    current_level           TEXT NOT NULL DEFAULT 'NONE',
    target_level            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'COLLECTING',
    phase                   TEXT NOT NULL DEFAULT 'AUTHENTICATING',
    collected_tokens        TEXT,
    validated_tokens        TEXT,
    attempt_counts          TEXT,
    active_path_index       TEXT,
    cross_brand_tokens      TEXT,
    candidate_parties       TEXT,
    matched_party           TEXT,
    customer_preferences    TEXT,
    disambiguation_attempt  INTEGER DEFAULT 0,
    transferred_from        TEXT,
    locked_until            TEXT,
    created_at              TEXT NOT NULL,
    last_activity_at        TEXT NOT NULL
);

-- Migration: add locked_until if upgrading from older schema
ALTER TABLE ivr_session ADD COLUMN locked_until TEXT;

-- Migration: add transferred_from for call transfer support
ALTER TABLE ivr_session ADD COLUMN transferred_from TEXT;

-- Migration: add phase, candidate_parties, matched_party, customer_preferences for disambiguation + preferences
ALTER TABLE ivr_session ADD COLUMN phase TEXT;
ALTER TABLE ivr_session ADD COLUMN candidate_parties TEXT;
ALTER TABLE ivr_session ADD COLUMN matched_party TEXT;
ALTER TABLE ivr_session ADD COLUMN customer_preferences TEXT;
ALTER TABLE ivr_session ADD COLUMN disambiguation_attempt INTEGER DEFAULT 0;
