-- Updates sent over WebSocket remain capped at 8192 bytes; only the
-- internal reconstructed checkpoint gets a larger, still bounded allowance.
ALTER TABLE document_streams DROP CONSTRAINT document_streams_checkpoint_check;
ALTER TABLE document_streams ADD CONSTRAINT document_streams_checkpoint_check
    CHECK (octet_length(checkpoint) BETWEEN 1 AND 65536);
