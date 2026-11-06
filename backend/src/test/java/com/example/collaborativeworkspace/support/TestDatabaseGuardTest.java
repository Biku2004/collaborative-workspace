package com.example.collaborativeworkspace.support;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "WORKSPACE_RUN_DB_TESTS", matches = "true")
class TestDatabaseGuardTest {
    @Test void aliasesCannotBypassIsolation() {
        String url = System.getenv("WORKSPACE_TEST_DB_URL"), user = System.getenv("WORKSPACE_TEST_DB_USER"), password = System.getenv("WORKSPACE_TEST_DB_PASSWORD");
        String alias = url.replace("127.0.0.1", "localhost");
        assertNotEquals(url, alias);
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.verify(url, user, password, alias, user, password));
        assertDoesNotThrow(TestDatabaseGuard::verifiedUrl);
    }
}
