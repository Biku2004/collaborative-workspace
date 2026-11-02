-- Existing unassigned documents remain untouched; all new or updated rows must be scoped.
ALTER TABLE documents ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
ALTER TABLE documents ADD CONSTRAINT documents_workspace_required CHECK (workspace_id IS NOT NULL) NOT VALID;
CREATE INDEX documents_workspace_id_idx ON documents (workspace_id, id);
