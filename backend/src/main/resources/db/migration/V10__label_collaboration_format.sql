-- Label existing history without rewriting it. Widen the constraint only when
-- the rich-text engine and capability-negotiated protocol are ready together.
ALTER TABLE documents ADD COLUMN collaboration_format VARCHAR(32) NOT NULL DEFAULT 'PLAIN_TEXT_V1';
ALTER TABLE documents ADD CONSTRAINT documents_collaboration_format_check CHECK (collaboration_format = 'PLAIN_TEXT_V1');
