-- A restore keeps all old updates/revisions and publishes a new epoch's immutable baseline.
CREATE TABLE document_restore_events (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision_id UUID NOT NULL REFERENCES document_revisions(id),
    actor_id UUID NOT NULL REFERENCES app_users(id),
    request_id UUID NOT NULL,
    from_epoch BIGINT NOT NULL CHECK (from_epoch >= 0),
    from_sequence BIGINT NOT NULL CHECK (from_sequence >= 0),
    to_epoch BIGINT NOT NULL CHECK (to_epoch = from_epoch + 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, actor_id, request_id),
    UNIQUE (document_id, to_epoch)
);
