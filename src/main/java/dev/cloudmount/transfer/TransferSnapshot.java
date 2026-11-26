package dev.cloudmount.transfer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

public record TransferSnapshot(long bytesTransferred, double speed, int active, int queued,
                               int errors, boolean outOfSpace, long cacheBytes, List<Item> items) {
    public static TransferSnapshot parse(JsonNode core, JsonNode vfs, JsonNode queue) {
        JsonNode disk = vfs.path("diskCache");
        var rows = new ArrayList<Item>();
        Set<String> activeNames = new HashSet<>();
        for (JsonNode item : core.path("transferring")) {
            long size = item.path("size").asLong();
            long done = item.path("bytes").asLong();
            String name = item.path("name").asText("File");
            activeNames.add(name);
            rows.add(new Item(name, "Transferring", size > 0 ? Math.min(1, (double) done / size) : -1,
                    bytes(done) + (size > 0 ? " / " + bytes(size) : "") + " · " + bytes((long) item.path("speed").asDouble()) + "/s"));
        }
        for (JsonNode item : queue.path("queue")) {
            String name = item.path("name").asText("File");
            if (!activeNames.contains(name))
                rows.add(new Item(name, item.path("uploading").asBoolean() ? "Uploading" : "Upload pending", -1, bytes(item.path("size").asLong())));
        }
        return new TransferSnapshot(core.path("bytes").asLong(), core.path("speed").asDouble(),
                Math.max(core.path("transferring").size(), disk.path("uploadsInProgress").asInt()),
                disk.path("uploadsQueued").asInt(), disk.path("erroredFiles").asInt(),
                disk.path("outOfSpace").asBoolean(), disk.path("bytesUsed").asLong(), List.copyOf(rows));
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        double n = value;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (n >= 1024 && unit < units.length - 1) {
            n /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", n, units[unit]);
    }

    public boolean hasPendingWrites() {
        return queued > 0 || active > 0 || errors > 0 || outOfSpace || !items.isEmpty();
    }

    public String status() {
        if (outOfSpace) return "Cache disk is full — free space before unmounting";
        if (errors > 0) return "Upload needs attention — check the activity log";
        if (active > 0) return "Transferring files";
        if (queued > 0 || !items.isEmpty()) return "Uploads waiting";
        return "No transfers pending";
    }

    public record Item(String name, String status, double progress, String detail) {
    }
}
