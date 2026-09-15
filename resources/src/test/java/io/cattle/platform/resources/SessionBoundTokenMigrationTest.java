package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class SessionBoundTokenMigrationTest {

    @Test
    public void addsSessionOwnershipWithoutRewritingTheHistoricalTokenTable() throws Exception {
        String changelog = read("content", "db", "changelog.xml");
        assertEquals(1, occurrences(changelog, "<include file=\"db/core-126.xml\"/>"));

        String migration = read("content", "db", "core-126.xml");
        assertTrue(migration.contains(
                "<changeSet author=\"PastureStack\" dbms=\"mysql,mariadb,postgresql\" "
                        + "id=\"pasturestack-session-bound-auth-token\">"));
        assertTrue(migration.contains("<preConditions onFail=\"HALT\">"));
        assertTrue(migration.contains("<tableExists tableName=\"auth_token\"/>"));
        assertTrue(migration.contains(
                "<columnExists tableName=\"auth_token\" columnName=\"client_session_id\"/>"));
        assertTrue(migration.contains(
                "<column name=\"client_session_id\" type=\"VARCHAR(128)\"/>"));
        assertTrue(migration.contains(
                "<createIndex indexName=\"idx_auth_token_client_session\" tableName=\"auth_token\">"));
        assertTrue(migration.contains("<column name=\"authenticated_as_account_id\"/>"));
        assertTrue(migration.contains("<column name=\"account_id\"/>"));

        String historical = read("content", "db", "core-058.xml");
        assertFalse(historical.contains("client_session_id"));

        // mysql-dump.sql is the historical bootstrap snapshot (it predates
        // later auth_token columns such as authenticated_as_account_id).
        // Adding the new column there as well as in core-126 would make a
        // fresh install hit the HALT precondition before Liquibase records
        // the change set.
        String mysqlDump = read("content", "db", "mysql", "mysql-dump.sql");
        assertFalse(mysqlDump.contains("client_session_id"));
    }

    private String read(String first, String... more) throws Exception {
        Path path = Paths.get(first, more);
        assertTrue("Required schema file is missing: " + path, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private int occurrences(String value, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
