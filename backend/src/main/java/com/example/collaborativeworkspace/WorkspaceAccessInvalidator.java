package com.example.collaborativeworkspace;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Profile("dev")
public class WorkspaceAccessInvalidator {
    private final DocumentRoomHub rooms;
    public WorkspaceAccessInvalidator(DocumentRoomHub rooms) { this.rooms = rooms; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reduced(WorkspaceAccessReduced event) { rooms.invalidate(event.workspaceId(), event.userId()); }
}
