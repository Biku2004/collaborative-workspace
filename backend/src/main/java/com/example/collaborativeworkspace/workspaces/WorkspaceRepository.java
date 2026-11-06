package com.example.collaborativeworkspace.workspaces;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbc;
    public WorkspaceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Workspace insertWorkspace(UUID id, String name, UUID userId) {
        return jdbc.queryForObject("INSERT INTO workspaces (id, name, created_by) VALUES (?, ?, ?) RETURNING id, name, created_at",
                (rs, row) -> new Workspace(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(), "OWNER"), id, name, userId);
    }

    public void insertOwner(UUID workspaceId, UUID userId) {
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", workspaceId, userId);
    }

    public List<Workspace> findByMember(UUID userId, int limit, UUID after) {
        return jdbc.query("SELECT w.id, w.name, w.created_at, m.role FROM workspaces w JOIN workspace_members m ON m.workspace_id = w.id WHERE m.user_id = ? AND (?::uuid IS NULL OR w.id > ?::uuid) ORDER BY w.id LIMIT ?",
                (rs, row) -> new Workspace(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("role")), userId, after, after, limit);
    }

    public boolean lockWorkspace(UUID workspaceId) {
        return !jdbc.query("SELECT id FROM workspaces WHERE id = ? FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class), workspaceId).isEmpty();
    }

    public Optional<WorkspaceRole> memberRole(UUID workspaceId, UUID userId) {
        return jdbc.query("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                (rs, row) -> WorkspaceRole.valueOf(rs.getString("role")), workspaceId, userId).stream().findFirst();
    }

    public int countOwners(UUID workspaceId) {
        return jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ? AND role = 'OWNER'", Integer.class, workspaceId);
    }

    public void upsertMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?) ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role", workspaceId, userId, role.name());
    }

    public void removeMember(UUID workspaceId, UUID userId) {
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", workspaceId, userId);
    }

    public List<WorkspaceMember> listMembers(UUID workspaceId, UUID actor, int limit, UUID after) {
        return jdbc.query("SELECT u.id, u.email, m.role FROM workspace_members actor LEFT JOIN workspace_members m ON m.workspace_id = actor.workspace_id AND (?::uuid IS NULL OR m.user_id > ?::uuid) LEFT JOIN app_users u ON u.id = m.user_id WHERE actor.workspace_id = ? AND actor.user_id = ? ORDER BY u.id LIMIT ?",
                (rs, row) -> rs.getObject("id") == null ? null : new WorkspaceMember(rs.getObject("id", UUID.class), rs.getString("email"), WorkspaceRole.valueOf(rs.getString("role"))), after, after, workspaceId, actor, limit);
    }
}
