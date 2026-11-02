CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(btrim(name)) > 0),
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX workspace_members_user_id_idx ON workspace_members (user_id, workspace_id);
