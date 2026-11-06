-- Rebuildable derived data. No update acceptance path depends on this table.
CREATE TABLE document_search_projection (
    document_id UUID PRIMARY KEY REFERENCES document_streams(document_id) ON DELETE CASCADE,
    epoch BIGINT CHECK (epoch >= 0),
    through_sequence BIGINT CHECK (through_sequence >= 0),
    content TEXT NOT NULL DEFAULT '' CHECK (octet_length(content) <= 131072),
    source_sha256 BYTEA CHECK (octet_length(source_sha256) = 32),
    indexed_at TIMESTAMPTZ,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    attempt_epoch BIGINT NOT NULL CHECK (attempt_epoch >= 0),
    attempt_sequence BIGINT NOT NULL CHECK (attempt_sequence >= 0),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count BETWEEN 0 AND 1000),
    failure_code VARCHAR(32),
    CHECK ((epoch IS NULL AND through_sequence IS NULL AND source_sha256 IS NULL AND indexed_at IS NULL AND content = '') OR
           (epoch IS NOT NULL AND through_sequence IS NOT NULL AND source_sha256 IS NOT NULL AND indexed_at IS NOT NULL))
);
CREATE INDEX document_search_terms ON document_search_projection USING GIN(search_vector);
