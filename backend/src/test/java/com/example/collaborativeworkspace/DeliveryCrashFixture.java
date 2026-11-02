package com.example.collaborativeworkspace;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Child JVM used only by the crash-recovery integration test. */
public class DeliveryCrashFixture {
    public static void main(String[] args) throws Exception {
        UUID actor = UUID.fromString(args[0]);
        String email = "crash-" + actor + "@example.com";
        var context = new SpringApplicationBuilder(CollaborativeWorkspaceApplication.class).run();
        context.getBean(UserRepository.class).create(actor, email, context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode("test-user-password"));
        UUID workspace = context.getBean(WorkspaceService.class).create(email, "crash fixture").id();
        UUID document = context.getBean(DocumentService.class).create(workspace, email, "crash fixture").id();
        byte[] payload = Base64.getDecoder().decode("AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA=");
        var saved = context.getBean(DocumentUpdateService.class).submit(workspace, document, actor, 0, UUID.randomUUID(), payload, () -> true);
        if (saved.sequence() != 1) throw new IllegalStateException("Unexpected sequence");
        System.out.println("COMMITTED:" + workspace + ":" + document);
        System.out.flush();
        new CountDownLatch(1).await(); // The parent deliberately kills this JVM after the commit.
    }
}
