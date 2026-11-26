package dev.cloudmount.transfer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Backend quota, including other Google services when the quota is shared.
 */
public record StorageSnapshot(long total, long used, long free) {
    public static StorageSnapshot parse(JsonNode about) {
        return new StorageSnapshot(about.path("total").asLong(-1), about.path("used").asLong(-1), about.path("free").asLong(-1));
    }

    public boolean known() {
        return total > 0 && free >= 0;
    }

    public double fraction() {
        return known() ? Math.clamp((double) (total - free) / total, 0, 1) : 0;
    }

    public String label() {
        if (!known()) return "This provider has not reported a storage quota.";
        return TransferSnapshot.bytes(free) + " free of " + TransferSnapshot.bytes(total);
    }
}
