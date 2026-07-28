package io.cattle.platform.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.bouncycastle.util.io.pem.PemObject;
import org.junit.Test;

public class CertSetTest {

    @Test
    public void writeLeavesZipStreamOpenForFollowingEntries() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        TestCertSet certSet = new TestCertSet();

        certSet.writeEntry(zip, "first.pem", new byte[] { 1 });
        certSet.writeEntry(zip, "second.pem", new byte[] { 2 });
        zip.close();

        assertEquals(Arrays.asList("first.pem", "second.pem"), zipEntryNames(output.toByteArray()));
    }

    private List<String> zipEntryNames(byte[] data) throws IOException {
        List<String> names = new ArrayList<String>();
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) {
            names.add(entry.getName());
            assertTrue(entry.getName(), entry.getSize() != 0);
            input.closeEntry();
        }
        input.close();
        return names;
    }

    private static class TestCertSet extends CertSet {
        TestCertSet() {
            super(null, null, null);
        }

        void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
            write(zip, name, new PemObject("TEST", data));
        }
    }
}
