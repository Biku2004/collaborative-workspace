-- Allow the new label in storage; application write and replay gates remain closed
-- until the rich-text collaboration path is ready.
ALTER TABLE documents DROP CONSTRAINT documents_collaboration_format_check;
ALTER TABLE documents ADD CONSTRAINT documents_collaboration_format_check
    CHECK (collaboration_format IN ('PLAIN_TEXT_V1', 'RICH_TEXT_V1'));

-- NULL identifies requests created before format-aware idempotency existed.
ALTER TABLE creation_requests ADD COLUMN request_variant VARCHAR(32);
