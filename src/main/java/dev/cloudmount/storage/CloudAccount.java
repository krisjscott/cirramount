package dev.cloudmount.storage;

public record CloudAccount(String id, String name, Provider provider, String remotePath) {
    public CloudAccount {
        if (id == null || !(id.equals("legacy") || id.matches("[0-9a-f-]{36}")))
            throw new IllegalArgumentException("Invalid connection ID.");
        if (!id.equals("legacy")) java.util.UUID.fromString(id);
        if (name == null || name.isBlank() || name.length() > 80 || name.matches("(?s).*\\p{Cntrl}.*"))
            throw new IllegalArgumentException("Give the connection a name of 1–80 characters.");
        if (provider == null) throw new IllegalArgumentException("Choose a storage provider.");
        remotePath = remotePath == null ? "" : remotePath.strip();
        if (remotePath.matches("(?s).*\\p{Cntrl}.*") || java.util.Arrays.asList(remotePath.split("/")).contains(".."))
            throw new IllegalArgumentException("Remote paths cannot contain control characters or parent-directory traversal.");
        if ((provider == Provider.S3 || provider == Provider.S3_COMPATIBLE) && (remotePath.isBlank() || remotePath.startsWith("/")))
            throw new IllegalArgumentException("Enter an S3 bucket, optionally followed by a folder prefix.");
    }

    public String remote() {
        return "cloudmount:" + remotePath;
    }

    @Override
    public String toString() {
        return name + " · " + provider;
    }
}
