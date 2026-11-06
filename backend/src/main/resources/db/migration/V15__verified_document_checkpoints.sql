-- Immutable snapshots are separate from the mutable per-edit validation cache.
-- Updates and outbox events are deliberately retained for replay and retry safety.
CREATE TABLE document_checkpoints (
    document_id UUID NOT NULL REFERENCES document_streams(document_id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL CHECK (epoch >= 0),
    through_sequence BIGINT NOT NULL CHECK (through_sequence >= 0),
    collaboration_format VARCHAR(32) NOT NULL CHECK (collaboration_format IN ('PLAIN_TEXT_V1', 'RICH_TEXT_V1')),
    checkpoint BYTEA NOT NULL CHECK (octet_length(checkpoint) BETWEEN 1 AND 65536),
    checkpoint_sha256 BYTEA NOT NULL CHECK (octet_length(checkpoint_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, epoch, through_sequence),
    CHECK (collaboration_format <> 'PLAIN_TEXT_V1' OR octet_length(checkpoint) <= 8192)
);
