package com.example.collaborativeworkspace.workspaces;

import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.common.pagination.PageSlice;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceMembershipService {
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final org.springframework.context.ApplicationEventPublisher events;

    public WorkspaceMembershipService(WorkspaceRepository workspaces, UserRepository users, org.springframework.context.ApplicationEventPublisher events) {
        this.workspaces = workspaces;
        this.users = users;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMember> list(UUID workspaceId, String actorEmail) {
        return list(workspaceId, actorEmail, 20, null).items();
    }

    @Transactional(readOnly = true)
    public PageSlice<WorkspaceMember> list(UUID workspaceId, String actorEmail, int limit, UUID after) {
        PageSlice.validateLimit(limit);
        List<WorkspaceMember> rows = workspaces.listMembers(workspaceId, actorId(actorEmail), limit + 1, after);
        if (rows.isEmpty()) throw new WorkspaceResourceNotFoundException();
        return PageSlice.from(rows.stream().filter(java.util.Objects::nonNull).toList(), limit, WorkspaceMember::userId);
    }

    // Every membership writer must lock the workspace first and recheck permissions afterwards.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkspaceMember setRole(UUID workspaceId, String actorEmail, ChangeWorkspaceMemberRequest request) {
        UUID actor = actorId(actorEmail);
        lockAndRequireOwner(workspaceId, actor);
        String email = request.email().toLowerCase(Locale.ROOT);
        UUID target = users.findIdByEmail(email).orElseThrow(WorkspaceResourceNotFoundException::new);
        var oldRole = workspaces.memberRole(workspaceId, target);
        protectLastOwner(workspaceId, oldRole.orElse(null), request.role());
        workspaces.upsertMember(workspaceId, target, request.role());
        if (oldRole.filter(role -> (role == WorkspaceRole.OWNER && request.role() != WorkspaceRole.OWNER) ||
                (role == WorkspaceRole.EDITOR && request.role() == WorkspaceRole.VIEWER)).isPresent())
            events.publishEvent(new WorkspaceAccessReduced(workspaceId, target));
        return new WorkspaceMember(target, email, request.role());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(UUID workspaceId, String actorEmail, UUID target) {
        UUID actor = actorId(actorEmail);
        lockAndRequireOwner(workspaceId, actor);
        WorkspaceRole oldRole = workspaces.memberRole(workspaceId, target).orElseThrow(WorkspaceResourceNotFoundException::new);
        protectLastOwner(workspaceId, oldRole, null);
        workspaces.removeMember(workspaceId, target);
        events.publishEvent(new WorkspaceAccessReduced(workspaceId, target));
    }

    private UUID actorId(String email) {
        return users.findIdByEmail(email).orElseThrow(() -> new AccessDeniedException("User is not available"));
    }

    private void lockAndRequireOwner(UUID workspaceId, UUID actor) {
        if (!workspaces.lockWorkspace(workspaceId)) throw new WorkspaceResourceNotFoundException();
        WorkspaceRole role = workspaces.memberRole(workspaceId, actor).orElseThrow(WorkspaceResourceNotFoundException::new);
        if (role != WorkspaceRole.OWNER) throw new AccessDeniedException("Owner permission required");
    }

    private void protectLastOwner(UUID workspaceId, WorkspaceRole oldRole, WorkspaceRole newRole) {
        if (oldRole == WorkspaceRole.OWNER && newRole != WorkspaceRole.OWNER && workspaces.countOwners(workspaceId) <= 1) {
            throw new LastWorkspaceOwnerException();
        }
    }
}
