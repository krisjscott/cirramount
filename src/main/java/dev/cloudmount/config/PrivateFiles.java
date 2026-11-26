package dev.cloudmount.config;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class PrivateFiles {
    private PrivateFiles() {
    }

    public static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    public static void write(Path path, String value) throws IOException {
        directory(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".write-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temp, value);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
