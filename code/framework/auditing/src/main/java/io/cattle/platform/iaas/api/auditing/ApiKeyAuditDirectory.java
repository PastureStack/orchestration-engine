package io.cattle.platform.iaas.api.auditing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Operator-selected directory; request metadata can name only validated direct children. */
final class ApiKeyAuditDirectory {
    private final Path configured;
    private final Path pinned;
    private final java.nio.file.attribute.UserPrincipal processOwner;
    private Object identity;
    private Boolean posix;
    private boolean unix;

    ApiKeyAuditDirectory(Path configured) {
        this.configured = configured.toAbsolutePath().normalize();
        try {
            // Retain relative/absolute paths and intentional administrator symlinks.
            // Canonicalization also works before a new spool directory exists.
            this.pinned = this.configured.toFile().getCanonicalFile().toPath();
            // A container may run as an unmapped numeric UID; user.name then
            // contains "?", not an OS principal. Linux procfs names this process
            // using its real service identity without trusting a JVM override.
            this.processOwner = System.getProperty("os.name", "").equals("Linux")
                    ? Files.getOwner(Path.of("/proc/self"))
                    : configured.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            if (pinned.getParent() == null) throw new IOException("Audit spool cannot be a filesystem root");
        } catch (IOException error) {
            throw new IllegalStateException("API-key audit directory is unavailable");
        }
    }

    synchronized Access open(boolean create) throws IOException {
        Path root = checkedRoot();
        if (create) Files.createDirectories(root, posix == null ? permissions(root, true) : privatePermissions(true));
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new IOException("Invalid audit directory");
        if (identity != null && !Objects.equals(identity, identity(attributes))) throw new IOException("Audit directory changed");
        if (posix == null) {
            var store = Files.getFileStore(root);
            posix = store.supportsFileAttributeView("posix");
            unix = store.supportsFileAttributeView("unix");
        }
        checkWriters(root);
        identity = identity(attributes);
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            validateOpenedDirectory(stream);
            checkedIdentity();
            return new Access(stream);
        } catch (IOException | RuntimeException error) {
            stream.close();
            throw error;
        }
    }

    void validateOpenedDirectory(DirectoryStream<Path> stream) throws IOException {
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            BasicFileAttributes opened = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
            if (!opened.isDirectory() || !Objects.equals(identity, identity(opened)))
                throw new IOException("Audit directory handle changed");
            var view = secure.getFileAttributeView(PosixFileAttributeView.class);
            if (view == null) throw new IOException("Audit directory access controls unavailable");
            checkWriters(view.readAttributes());
        }
    }

    private Path checkedRoot() throws IOException {
        Path current = configured.toFile().getCanonicalFile().toPath();
        // This is a live comparison against the root pinned at construction, not
        // a request-controlled prefix or a silent change of configuration.
        if (!current.equals(pinned)) throw new IOException("Audit directory mapping changed");
        return current;
    }

    private void checkedIdentity() throws IOException {
        Path root = checkedRoot();
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !Objects.equals(identity, identity(attributes))) throw new IOException("Audit directory changed");
    }

    private static Object identity(BasicFileAttributes attributes) {
        // Windows exposes no fileKey. Its fallback also requires the private ACL
        // below; Linux uses inode identity plus directory-relative secure I/O.
        return attributes.fileKey() == null ? attributes.creationTime() : attributes.fileKey();
    }

    private static FileAttribute<?>[] permissions(Path path, boolean directory) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return Files.getFileStore(existing).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))}
                : new FileAttribute<?>[0];
    }

    private FileAttribute<?>[] privatePermissions(boolean directory) {
        return Boolean.TRUE.equals(posix)
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))}
                : new FileAttribute<?>[0];
    }

    private void checkWriters(Path path) throws IOException {
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (!owner.equals(processOwner)) throw new IOException("Audit path is not owned by the service account");
        if (Boolean.TRUE.equals(posix)) {
            checkWriters(Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Audit path access controls unavailable");
            Set<AclEntryPermission> writes = Set.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.DELETE_CHILD, AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
            for (var entry : view.getAcl()) {
                String principal = entry.principal().getName();
                boolean trusted = entry.principal().equals(owner) || principal.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                        || principal.equalsIgnoreCase("BUILTIN\\Administrators");
                if (!trusted && entry.type() == AclEntryType.ALLOW && !entry.flags().contains(java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY)
                        && entry.permissions().stream().anyMatch(writes::contains))
                    throw new IOException("Audit path permits untrusted writes");
            }
        }
    }

    private void checkWriters(PosixFileAttributes attributes) throws IOException {
        if (!attributes.owner().equals(processOwner)) throw new IOException("Audit path is not owned by the service account");
        if (attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE))
            throw new IOException("Audit path permits untrusted writes");
    }

    final class Access implements AutoCloseable {
        private final DirectoryStream<Path> stream;
        private final SecureDirectoryStream<Path> secure;

        private Access(DirectoryStream<Path> stream) {
            this.stream = stream;
            this.secure = stream instanceof SecureDirectoryStream<Path> opened ? opened : null;
        }

        Path leaf(String name) throws IOException {
            Path child = pinned.resolve(name).normalize();
            if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || !pinned.equals(child.getParent())
                    || !child.getFileName().toString().equals(name)) throw new IOException("Invalid audit filename");
            return child.getFileName();
        }

        private Path absolute(Path name) throws IOException {
            checkedIdentity();
            name = leaf(name.toString());
            Path path = checkedRoot().resolve(name).normalize();
            if (!path.getParent().equals(pinned)) throw new IOException("Audit path escapes directory");
            return path;
        }

        private BasicFileAttributes attributes(Path name) throws IOException {
            name = leaf(name.toString());
            BasicFileAttributes result = secure == null
                    ? Files.readAttributes(absolute(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    : secure.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!result.isRegularFile() || result.isSymbolicLink()) throw new IOException("Invalid audit file");
            if (secure != null) {
                var view = secure.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) throw new IOException("Audit file access controls unavailable");
                checkWriters(view.readAttributes());
            }
            Path path = absolute(name);
            if (secure == null) checkWriters(path);
            if (unix && ((Number) Files.getAttribute(path, "unix:nlink",
                    LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw new IOException("Linked audit file rejected");
            return result;
        }

        boolean exists(Path name) throws IOException {
            try { attributes(name); return true; }
            catch (NoSuchFileException missing) { return false; }
        }

        FileChannel openFile(Path name, OpenOption... options) throws IOException {
            name = leaf(name.toString());
            // Existing entries must be regular, unlinked files. CREATE_NEW is
            // used for the spool temp; lock creation keeps the established inode.
            exists(name);
            Set<OpenOption> flags = new HashSet<>(Set.of(options));
            flags.add(LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel = secure == null
                    ? FileChannel.open(absolute(name), flags, privatePermissions(false))
                    : secure.newByteChannel(name, flags, privatePermissions(false));
            try {
                attributes(name);
                if (!(channel instanceof FileChannel file)) throw new IOException("Durable audit channel unavailable");
                return file;
            } catch (IOException | RuntimeException error) {
                channel.close();
                throw error;
            }
        }

        List<Path> pending(int maximum) throws IOException {
            List<Path> result = new ArrayList<>();
            try (DirectoryStream<Path> children = secure == null ? Files.newDirectoryStream(checkedRoot())
                    : secure.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS)) {
                for (Path item : children) {
                    String name = item.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    if (!name.matches("(?:[0-9]+-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|terminal-[a-f0-9]{64})\\.json"))
                        throw new IOException("Unrecognized audit event filename");
                    Path child = leaf(name);
                    attributes(child);
                    result.add(child);
                    if (result.size() > maximum) throw new IOException("Audit event count exceeds bound");
                }
            }
            result.sort(Comparator.comparing(Path::toString));
            return result;
        }

        long size(Path name) throws IOException { return attributes(name).size(); }

        byte[] read(Path name, int limit) throws IOException {
            try (FileChannel channel = openFile(name, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
                if (buffer.position() > limit) throw new IOException("Audit event exceeds metadata bound");
                byte[] bytes = new byte[buffer.position()];
                buffer.flip(); buffer.get(bytes);
                return bytes;
            }
        }

        void delete(Path name) throws IOException {
            attributes(name);
            if (secure == null) Files.delete(absolute(name));
            else secure.deleteFile(name);
        }

        void move(Path from, Path to) throws IOException {
            attributes(from);
            if (exists(to)) throw new IOException("Audit event already exists");
            if (secure == null) Files.move(absolute(from), absolute(to), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            else secure.move(from, secure, to);
        }

        void force() throws IOException {
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                checkedIdentity();
                try (SeekableByteChannel channel = secure == null ? FileChannel.open(checkedRoot(), StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS) : secure.newByteChannel(Path.of("."), Set.of(StandardOpenOption.READ))) {
                    if (!(channel instanceof FileChannel file)) throw new IOException("Audit directory sync unavailable");
                    file.force(true);
                }
            }
        }

        @Override public void close() throws IOException { stream.close(); }
    }
}
