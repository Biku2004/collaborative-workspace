CREATE TABLE document_revisions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    epoch BIGINT NOT NULL CHECK (epoch >= 0),
    through_sequence BIGINT NOT NULL CHECK (through_sequence >= 0),
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    request_id UUID NOT NULL,
    collaboration_format VARCHAR(32) NOT NULL CHECK (collaboration_format IN ('PLAIN_TEXT_V1', 'RICH_TEXT_V1')),
    checkpoint BYTEA NOT NULL CHECK (octet_length(checkpoint) BETWEEN 1 AND 65536),
    checkpoint_sha256 BYTEA NOT NULL CHECK (octet_length(checkpoint_sha256) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, created_by, request_id),
    CHECK (collaboration_format <> 'PLAIN_TEXT_V1' OR octet_length(checkpoint) <= 8192)
);
CREATE INDEX document_revisions_page ON document_revisions(document_id, created_at DESC, id DESC);
