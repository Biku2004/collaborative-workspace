package com.example.collaborativeworkspace.support;

import java.sql.DriverManager;

public final class TestDatabaseGuard {
    private TestDatabaseGuard() {}

    public static String verifiedUrl() {
        String url = required("WORKSPACE_TEST_DB_URL");
        verify(url, required("WORKSPACE_TEST_DB_USER"), required("WORKSPACE_TEST_DB_PASSWORD"),
                required("WORKSPACE_DB_URL"), required("WORKSPACE_DB_USER"), required("WORKSPACE_DB_PASSWORD"));
        return url;
    }

    // Conservative: equal database names are rejected even on different servers.
    public static void verify(String testUrl, String testUser, String testPassword, String devUrl, String devUser, String devPassword) {
        String test = databaseName(testUrl, testUser, testPassword);
        String dev = databaseName(devUrl, devUser, devPassword);
        if (!test.equals("collaborative_workspace_test") || test.equals(dev)) {
            throw new IllegalStateException("Tests require the dedicated collaborative_workspace_test database, separate from development");
        }
    }

    private static String databaseName(String url, String user, String password) {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT current_database()")) {
                    if (!result.next()) throw new IllegalStateException("Cannot identify database");
                    return result.getString(1);
                }
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Cannot verify test database isolation", ex);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
