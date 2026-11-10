-- Persist the server-generated operation correlation across relay restarts.
-- Old events use their existing random event ID; no extension or payload rewrite is needed.
ALTER TABLE document_update_outbox ADD COLUMN correlation_id UUID;
UPDATE document_update_outbox SET correlation_id = event_id;
ALTER TABLE document_update_outbox ALTER COLUMN correlation_id SET NOT NULL;
ALTER TABLE document_update_outbox ALTER COLUMN correlation_id SET DEFAULT gen_random_uuid();
CREATE INDEX document_outbox_pending_age ON document_update_outbox(created_at) WHERE published_at IS NULL;
