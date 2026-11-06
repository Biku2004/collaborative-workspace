package com.example.collaborativeworkspace.search;

import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.common.api.InvalidUserInputException;
import com.example.collaborativeworkspace.workspaces.WorkspaceRepository;
import com.example.collaborativeworkspace.workspaces.WorkspaceResourceNotFoundException;
import com.example.collaborativeworkspace.workspaces.WorkspaceRole;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("dev")
public class DocumentSearchService {
    public record Results(List<DocumentSearchRepository.Match> items, boolean hasMore, DocumentSearchRepository.Coverage coverage, boolean indexingEnabled) {}
    private final DocumentSearchRepository search;
    private final WorkspaceRepository workspaces;
    private final CollaborationFormatPolicy formats;
    private final boolean enabled;
    private final TransactionTemplate transaction;
    public DocumentSearchService(DocumentSearchRepository search, WorkspaceRepository workspaces, CollaborationFormatPolicy formats,
            @Value("${workspace.search.indexing.enabled:false}") boolean enabled, PlatformTransactionManager manager) {
        this.search = search; this.workspaces = workspaces; this.formats = formats; this.enabled = enabled; transaction = new TransactionTemplate(manager);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Results search(UUID workspace, UUID actor, String query, int limit) {
        if (workspaces.memberRole(workspace, actor).isEmpty()) throw new WorkspaceResourceNotFoundException();
        String text = query == null ? "" : query.strip();
        if (text.isBlank() || text.length() > 200 || text.chars().anyMatch(Character::isISOControl) || limit < 1 || limit > 50) throw new InvalidUserInputException();
        boolean rich = formats.supports("RICH_TEXT_V1");
        var rows = search.search(workspace, actor, text, limit + 1, rich);
        return new Results(List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows), rows.size() > limit, search.coverage(workspace, actor, rich), enabled);
    }
    public int rebuild(UUID workspace, UUID actor, Object sessionGuard, BooleanSupplier sessionValid) {
        synchronized (java.util.Objects.requireNonNull(sessionGuard)) {
            return java.util.Objects.requireNonNull(transaction.execute(status -> {
                if (!workspaces.lockWorkspace(workspace)) throw new WorkspaceResourceNotFoundException();
                var role = workspaces.memberRole(workspace, actor).orElseThrow(WorkspaceResourceNotFoundException::new);
                if (role != WorkspaceRole.OWNER || !sessionValid.getAsBoolean()) throw new AccessDeniedException("Owner session required");
                return search.clear(workspace);
            }));
        }
    }
}
