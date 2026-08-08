package io.cattle.platform.configitem.server.model.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;

import io.cattle.platform.configitem.model.Client;
import io.cattle.platform.configitem.model.ItemVersion;
import io.cattle.platform.configitem.server.model.Request;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

public class ArchiveChecksumTest {

    @Test
    public void emitsSha256ChecksumMetadata() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes);
        ArchiveContext context = new ArchiveContext(new TestRequest(), tar, "1");
        context.hashes = new HashMap<String, String>();

        AbstractArchiveBasedConfigItem.withEntry(context, "payload", 4,
                output -> output.write("data".getBytes(StandardCharsets.UTF_8)));
        AbstractArchiveBasedConfigItem.writeHashes(context);
        tar.close();

        boolean sums = false;
        boolean sumOfSums = false;
        try (TarArchiveInputStream input = new TarArchiveInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            TarArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String content = new String(IOUtils.toByteArray(input), StandardCharsets.UTF_8);
                if (entry.getName().endsWith(AbstractArchiveBasedConfigItem.CHECKSUMS)) {
                    sums = true;
                    assertTrue(content.matches("(?s)[0-9a-f]{64} \\*.*payload\\n"));
                }
                if (entry.getName().endsWith(AbstractArchiveBasedConfigItem.CHECKSUMS_SUM)) {
                    sumOfSums = true;
                    assertTrue(content.matches("(?s)[0-9a-f]{64} \\*.*SHA256SUMS\\n"));
                }
            }
        }
        assertTrue(sums);
        assertTrue(sumOfSums);
    }

    @Test
    public void checksumNamesMatchAgentVerifierContract() {
        assertEquals("SHA256SUMS", AbstractArchiveBasedConfigItem.CHECKSUMS);
        assertEquals("SHA256SUMSSUM", AbstractArchiveBasedConfigItem.CHECKSUMS_SUM);
    }

    private static final class TestRequest implements Request {
        @Override
        public ItemVersion getAppliedVersion() { return null; }
        @Override
        public ItemVersion getCurrentVersion() { return null; }
        @Override
        public String getItemName() { return "test"; }
        @Override
        public Client getClient() { return null; }
        @Override
        public void setResponseCode(int code) { }
        @Override
        public void setContentType(String contentType) { }
        @Override
        public void setContentEncoding(String contentEncoding) { }
        @Override
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override
        public Map<String, Object> getParams() { return Collections.emptyMap(); }
    }
}
