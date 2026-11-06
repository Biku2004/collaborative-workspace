package com.example.collaborativeworkspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class DocumentFormatGuardTest {
    @Test void unsupportedFormatsAreRejectedBeforeReconstructionOrReplay() {
        var updates = mock(DocumentUpdateRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var engine = mock(YjsEngineClient.class);
        var rooms = mock(DocumentRoomHub.class);
        var transaction = mock(PlatformTransactionManager.class);
        UUID workspace = UUID.randomUUID(), document = UUID.randomUUID(), actor = UUID.randomUUID();
        var snapshot = new DocumentUpdateRepository.Snapshot(0, 0, new byte[] {0, 0}, WorkspaceRole.OWNER, false, "RICH_TEXT_V1");
        when(updates.findAccessible(workspace, document, actor)).thenReturn(Optional.of(snapshot));

        var formats = new CollaborationFormatPolicy(false);
        var writer = new DocumentUpdateService(updates, workspaces, engine, formats, transaction);
        var writeFailure = assertThrows(DocumentUpdateException.class,
                () -> writer.submit(workspace, document, actor, 0, UUID.randomUUID(), new byte[] {0, 0}, () -> true));
        assertEquals("SCHEMA_UNSUPPORTED", writeFailure.code());
        verifyNoInteractions(engine);

        var delivery = new DocumentDeliveryService(updates, rooms, JsonMapper.builder().build(), formats);
        var readFailure = assertThrows(DocumentUpdateException.class, () -> delivery.sync(workspace, document, actor, 0, 0));
        assertEquals("SCHEMA_UNSUPPORTED", readFailure.code());
        verify(updates, never()).findAfter(any(), anyLong(), anyLong(), anyLong(), anyInt());

        var storedCheckpoints = mock(DocumentCheckpointRepository.class);
        var budget = new DocumentSnapshotBudget();
        var checkpoints = new DocumentCheckpointService(updates, storedCheckpoints, workspaces, engine, formats, transaction, budget);
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(CheckpointException.class, () -> checkpoints.create(workspace, document, actor)).code());
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(CheckpointException.class, () -> checkpoints.latest(workspace, document, actor)).code());
        verifyNoInteractions(storedCheckpoints, engine);
        var storedRevisions = mock(DocumentRevisionRepository.class);
        var revisions = new DocumentRevisionService(updates, storedRevisions, workspaces, formats, engine, transaction, budget);
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(RevisionException.class, () -> revisions.create(workspace, document, actor,
                new CreateRevisionRequest("Rich", UUID.randomUUID()), () -> true)).code());
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(RevisionException.class, () -> revisions.list(workspace, document, actor, 20, null)).code());
        verifyNoInteractions(storedRevisions, engine);
        var restores = new DocumentRestoreService(updates, revisions, storedCheckpoints, mock(DocumentRestoreRepository.class), workspaces, formats, engine, budget, transaction);
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(RevisionException.class, () -> restores.baseline(workspace, document, actor)).code());
        assertEquals("SCHEMA_UNSUPPORTED", assertThrows(RevisionException.class, () -> restores.restore(workspace, document, actor,
                new RestoreRevisionRequest(UUID.randomUUID(), UUID.randomUUID(), 0L, 0L), () -> true)).code());
        verifyNoInteractions(storedRevisions, engine);
    }
}
