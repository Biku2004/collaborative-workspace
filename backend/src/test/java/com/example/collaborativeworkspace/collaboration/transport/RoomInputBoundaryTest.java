package com.example.collaborativeworkspace.collaboration.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.collaborativeworkspace.collaboration.engine.CollaborationFormatPolicy;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateRepository;
import com.example.collaborativeworkspace.collaboration.updates.DocumentUpdateService;
import com.example.collaborativeworkspace.documents.DocumentRepository;
import com.example.collaborativeworkspace.workspaces.*;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

class RoomInputBoundaryTest {
    WebSocketSession socket(UUID workspace, UUID user) {
        var socket=mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new HashMap<>(Map.of("workspaceId",workspace.toString(),"userId",user.toString(),"userEmail","test@example.com","httpSession",mock(HttpSession.class))));
        return socket;
    }
    @Test void oneAccountCannotConsumeOtherWorkspacesConnectionCapacity() {
        var hub=new DocumentRoomHub(mock(DocumentRepository.class),mock(WorkspaceRepository.class),JsonMapper.builder().build());
        UUID workspace=UUID.randomUUID(), actor=UUID.randomUUID();
        try {
            int admitted=0;
            // In-memory fake sockets only: no real connection or network load.
            for(int i=0;i<256;i++) if(hub.add(socket(workspace,actor))!=null) admitted++;
            int actual=admitted;
            var other=hub.add(socket(UUID.randomUUID(),UUID.randomUUID()));
            assertAll(()->assertEquals(8,actual,"Per-account cap"),()->assertNotNull(other,"Other users retain capacity"));
        } finally { hub.shutdown(); }
    }
    @Test void concurrentReservationsStayBoundedAndClosureReleasesCapacity() throws Exception {
        var hub=new DocumentRoomHub(mock(DocumentRepository.class),mock(WorkspaceRepository.class),JsonMapper.builder().build());
        var workers=Executors.newFixedThreadPool(8);
        UUID workspace=UUID.randomUUID(), actor=UUID.randomUUID();
        try {
            var futures=new ArrayList<Future<DocumentRoomHub.Peer>>();
            for(int i=0;i<24;i++) futures.add(workers.submit(()->hub.add(socket(workspace,actor))));
            var accepted=new ArrayList<DocumentRoomHub.Peer>();
            for(var future:futures) { var peer=future.get(5,TimeUnit.SECONDS); if(peer!=null) accepted.add(peer); }
            assertEquals(8,accepted.size());
            hub.remove(accepted.getFirst().socket);
            assertNotNull(hub.add(socket(workspace,actor)));
            assertEquals(8,hub.count());
        } finally { workers.shutdownNow(); hub.shutdown(); }
    }
    @Test void socketsThatNeverGreetExpire() throws Exception {
        var workspaces=mock(WorkspaceRepository.class);
        when(workspaces.memberRole(any(),any())).thenReturn(Optional.of(WorkspaceRole.VIEWER));
        var clock=new AtomicLong();
        var json=JsonMapper.builder().build();
        var hub=new DocumentRoomHub(mock(DocumentRepository.class),workspaces,json,clock::get);
        var socket=socket(UUID.randomUUID(),UUID.randomUUID());
        try {
            hub.add(socket); clock.set(TimeUnit.SECONDS.toNanos(11));
            hub.notifyHeads(mock(DocumentUpdateRepository.class),json);
            verify(socket).close(CloseStatus.POLICY_VIOLATION);
            assertEquals(0,hub.count());
        } finally { hub.shutdown(); }
    }
    @Test void greetedIdleSocketsRemainUsableAndDuplicateIdsDoNotReserveSlots() throws Exception {
        var workspaces=mock(WorkspaceRepository.class);
        when(workspaces.memberRole(any(),any())).thenReturn(Optional.of(WorkspaceRole.VIEWER));
        var clock=new AtomicLong(); var json=JsonMapper.builder().build();
        var hub=new DocumentRoomHub(mock(DocumentRepository.class),workspaces,json,clock::get);
        var socket=socket(UUID.randomUUID(),UUID.randomUUID());
        try {
            var peer=hub.add(socket); peer.welcomed=true;
            assertNull(hub.add(socket)); assertEquals(1,hub.count());
            clock.set(TimeUnit.SECONDS.toNanos(11));
            hub.notifyHeads(mock(DocumentUpdateRepository.class),json);
            verify(socket,never()).close(any()); assertEquals(1,hub.count());
            hub.remove(socket); hub.remove(socket); assertEquals(0,hub.count());
        } finally { hub.shutdown(); }
    }
    @ParameterizedTest @ValueSource(strings={""," ","{","null","[]","1","true","{}","{\"type\":\"HELLO\"}","{\"type\":\"HELLO\",\"version\":1.0}","{\"type\":\"HELLO\",\"version\":-1}","{\"type\":\"HELLO\",\"version\":9223372036854775808}","{\"type\":\"HELLO\",\"version\":\"1\"}","{\"type\":\"HELLO\",\"version\":1,\"extra\":true}","{\"type\":\"JOIN\",\"version\":1,\"documentId\":\"bad\"}"})
    void malformedGreetingCannotReachUpdatesOrEscapeHandler(String payload) throws Exception {
        var workspaces=mock(WorkspaceRepository.class);
        when(workspaces.memberRole(any(),any())).thenReturn(Optional.of(WorkspaceRole.OWNER));
        var json=JsonMapper.builder().build();
        var hub=new DocumentRoomHub(mock(DocumentRepository.class),workspaces,json);
        var updates=mock(DocumentUpdateService.class);
        var handler=new WorkspaceSocketHandler(mock(SocketSessionRegistry.class),json,hub,updates,mock(DocumentDeliveryService.class),mock(CollaborationFormatPolicy.class));
        var socket=socket(UUID.randomUUID(),UUID.randomUUID());
        try {
            hub.add(socket);
            assertDoesNotThrow(()->handler.handleMessage(socket,new TextMessage(payload)));
            verify(socket).close(any(CloseStatus.class));
            verifyNoInteractions(updates);
        } finally { hub.shutdown(); }
    }
}
