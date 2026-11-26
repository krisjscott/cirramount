package dev.cloudmount.mount;

import java.io.IOException;
import java.nio.file.*;

public final class MountTable {
    private MountTable() {
    }

    public static boolean mounted(Path mount) throws IOException {
        return contains(Files.readString(Path.of("/proc/self/mountinfo")), mount, false);
    }

    public static boolean rcloneMounted(Path mount) throws IOException {
        return contains(Files.readString(Path.of("/proc/self/mountinfo")), mount, true);
    }

    public static boolean owns(Path mount, String deviceName) throws IOException {
        String expected = mount.toAbsolutePath().normalize().toString();
        for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
            String[] halves = line.split(" - ", 2);
            String[] fields = halves[0].split(" ");
            if (fields.length < 5 || halves.length != 2 || !decode(fields[4]).equals(expected)) continue;
            String[] fs = halves[1].split(" ");
            if (fs.length >= 2 && fs[0].equals("fuse.rclone") && decode(fs[1]).equals(deviceName)) return false;
        }
        return true;
    }

    public static boolean contains(String table, Path mount, boolean rcloneOnly) {
        String expected = mount.toAbsolutePath().normalize().toString();
        return table.lines().anyMatch(line -> {
            String[] halves = line.split(" - ", 2);
            String[] fields = halves[0].split(" ");
            if (fields.length < 5 || !decode(fields[4]).equals(expected)) return false;
            return !rcloneOnly || (halves.length == 2 && halves[1].startsWith("fuse.rclone "));
        });
    }

    private static String decode(String raw) {
        return raw.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
    }
}
