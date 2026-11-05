-- Wire updates remain limited to 8192 bytes. The server's reconstructed
-- checkpoint can be larger because it contains a document's accumulated state.
ALTER TABLE document_streams DROP CONSTRAINT document_streams_checkpoint_check;
ALTER TABLE document_streams ADD CONSTRAINT document_streams_checkpoint_check
    CHECK (octet_length(checkpoint) BETWEEN 1 AND 32768);
