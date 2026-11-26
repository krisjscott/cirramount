package dev.cloudmount.config;

import java.nio.file.Path;

public record MountSettings(String rcloneBinary, String mountPath, String cachePath,
                            int cacheGiB, int maxAgeHours, int writeBackSeconds) {
    public static MountSettings defaults(AppPaths paths) {
        Path bundled = Path.of("tools/rclone").toAbsolutePath();
        return new MountSettings(java.nio.file.Files.isExecutable(bundled) ? bundled.toString() : "rclone",
                Path.of(System.getProperty("user.home"), "Cloud", "GoogleDrive").toString(),
                paths.cacheDir().toString(), 20, 72, 5);
    }

    private static Path absolute(String value, String label) {
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute())
            throw new IllegalArgumentException(label + " must be an absolute path.");
        return Path.of(value).normalize();
    }

    public void validate() {
        if (rcloneBinary == null || rcloneBinary.isBlank())
            throw new IllegalArgumentException("Choose the rclone executable.");
        Path mount = absolute(mountPath, "Mount folder");
        Path cache = absolute(cachePath, "Cache folder");
        if (mount.getParent() == null || mount.equals(Path.of(System.getProperty("user.home"))))
            throw new IllegalArgumentException("Choose a dedicated empty mount folder.");
        if (cache.startsWith(mount) || mount.startsWith(cache))
            throw new IllegalArgumentException("Mount and cache folders must be separate, not inside one another.");
        if (cacheGiB < 1 || cacheGiB > 10000)
            throw new IllegalArgumentException("Cache size must be between 1 and 10,000 GiB.");
        if (maxAgeHours < 1 || maxAgeHours > 8760)
            throw new IllegalArgumentException("Cache age must be between 1 and 8,760 hours.");
        if (writeBackSeconds < 1 || writeBackSeconds > 600)
            throw new IllegalArgumentException("Upload delay must be between 1 and 600 seconds.");
    }
}
