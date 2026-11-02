ALTER TABLE document_update_outbox ADD COLUMN published_at TIMESTAMPTZ;
CREATE INDEX document_update_outbox_pending_idx ON document_update_outbox (document_id, epoch, sequence) WHERE published_at IS NULL;
