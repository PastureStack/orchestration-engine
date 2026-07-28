package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class CredentialSecretCapacityMigrationTest {

    @Test
    public void expandsCredentialSecretsWithoutRewritingTheHistoricalBaseline() throws Exception {
        String changelog = read("content", "db", "changelog.xml");
        assertEquals(1, occurrences(changelog, "<include file=\"db/core-125.xml\"/>"));

        String migration = read("content", "db", "core-125.xml");
        assertTrue(migration.contains(
                "<changeSet author=\"PastureStack\" "
                        + "id=\"pasturestack-credential-secret-value-mediumtext\">"));
        assertTrue(migration.contains("<preConditions onFail=\"HALT\">"));
        assertTrue(migration.contains("<tableExists tableName=\"credential\"/>"));
        assertTrue(migration.contains(
                "<columnExists tableName=\"credential\" columnName=\"secret_value\"/>"));
        assertTrue(migration.contains(
                "<modifyDataType tableName=\"credential\" columnName=\"secret_value\" "
                        + "newDataType=\"${mediumtext}\"/>"));
        assertTrue(migration.contains(
                "<property name=\"mediumtext\" value=\"MEDIUMTEXT(16777215)\" />"));

        String baseline = read("content", "db", "core-001.xml");
        assertTrue(baseline.contains(
                "<column name=\"secret_value\" type=\"VARCHAR(4096)\"/>"));

        String mysqlDump = read("content", "db", "mysql", "mysql-dump.sql");
        assertTrue(mysqlDump.contains("`secret_value` mediumtext,"));
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
