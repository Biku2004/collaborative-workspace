-- Streams are created lazily for scoped documents; existing unassigned rows stay untouched.
CREATE TABLE document_streams (
    document_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    head_sequence BIGINT NOT NULL DEFAULT 0 CHECK (head_sequence >= 0),
    checkpoint BYTEA NOT NULL CHECK (octet_length(checkpoint) BETWEEN 1 AND 8192)
);

CREATE TABLE document_updates (
    document_id UUID NOT NULL REFERENCES document_streams(document_id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL CHECK (epoch >= 0),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    update_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_users(id),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 8192),
    payload_sha256 BYTEA NOT NULL CHECK (octet_length(payload_sha256) = 32),
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, epoch, sequence),
    UNIQUE (document_id, epoch, update_id)
);

CREATE TABLE document_update_outbox (
    event_id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    epoch BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, epoch, sequence),
    FOREIGN KEY (document_id, epoch, sequence) REFERENCES document_updates(document_id, epoch, sequence) ON DELETE CASCADE
);
