package dev.cloudmount.storage;

import java.util.*;

public enum Provider {
    GOOGLE("Google Drive", "drive", true),
    S3("Amazon S3", "s3", false),
    S3_COMPATIBLE("S3-compatible storage", "s3", false),
    ONEDRIVE("Microsoft OneDrive", "onedrive", true),
    DROPBOX("Dropbox", "dropbox", true),
    WEBDAV("WebDAV", "webdav", false),
    SFTP("SFTP", "sftp", false);

    private final String label, backend;
    private final boolean oauth;

    Provider(String label, String backend, boolean oauth) {
        this.label = label;
        this.backend = backend;
        this.oauth = oauth;
    }

    private static Field f(String key, String label, String value, boolean secret, boolean required) {
        return new Field(key, label, value, secret, required);
    }

    private static void url(String value) {
        var uri = java.net.URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter an HTTP(S) endpoint without credentials in the URL.");
    }

    public String backend() {
        return backend;
    }

    public boolean oauth() {
        return oauth;
    }

    public List<Field> fields() {
        return switch (this) {
            case GOOGLE ->
                    List.of(f("client_id", "Google client ID (uses app setup if blank)", "", false, false),
                            f("client_secret", "Google client secret", "", true, false));
            case ONEDRIVE, DROPBOX ->
                    List.of(f("client_id", "App client ID (optional)", "", false, false), f("client_secret", "App client secret (optional)", "", true, false));
            case S3, S3_COMPATIBLE ->
                    List.of(f("access_key_id", "Access key ID (blank uses AWS credential chain)", "", true, false), f("secret_access_key", "Secret access key", "", true, false),
                            f("session_token", "Session token (optional)", "", true, false), f("region", "Region", this == S3 ? "us-east-1" : "", false, false),
                            f("endpoint", "Endpoint URL", "", false, this == S3_COMPATIBLE), f("profile", "AWS profile (optional)", "", false, false));
            case WEBDAV ->
                    List.of(f("url", "WebDAV URL", "https://", false, true), f("vendor", "Vendor (other / nextcloud / owncloud)", "other", false, true), f("user", "Username", "", false, false), f("pass", "Password or app password", "", true, false));
            case SFTP ->
                    List.of(f("host", "Server hostname", "", false, true), f("port", "Port", "22", false, true), f("user", "Username", "", false, true), f("pass", "Password (or use a key below)", "", true, false),
                            f("key_file", "Private key file (optional)", "", false, false), f("key_file_pass", "Key passphrase (optional)", "", true, false), f("known_hosts_file", "Known hosts file (required for host verification)", System.getProperty("user.home") + "/.ssh/known_hosts", false, true));
        };
    }

    public Map<String, String> parameters(Map<String, String> raw) {
        var params = new LinkedHashMap<String, String>();
        for (Field f : fields()) {
            String value = raw.getOrDefault(f.key(), "");
            if (!f.secret()) value = value.strip();
            if (f.required() && value.isBlank()) throw new IllegalArgumentException(f.label() + " is required.");
            if (value.contains("\n") || value.contains("\r"))
                throw new IllegalArgumentException(f.label() + " must be a single line.");
            if (!value.isBlank()) params.put(f.key(), value);
        }
        if (this == GOOGLE) params.put("scope", "drive");
        if (this == S3 || this == S3_COMPATIBLE) {
            params.put("provider", this == S3 ? "AWS" : "Other");
            boolean key = params.containsKey("access_key_id"), secret = params.containsKey("secret_access_key");
            if (key != secret)
                throw new IllegalArgumentException("Supply both S3 access key and secret, or leave both blank for the AWS credential chain.");
            params.put("env_auth", Boolean.toString(!key));
            if (this == S3_COMPATIBLE) params.put("force_path_style", "true");
        }
        if (params.containsKey("endpoint")) url(params.get("endpoint"));
        if (this == WEBDAV) url(params.get("url"));
        if (this == SFTP) {
            int port;
            try {
                port = Integer.parseInt(params.get("port"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("SFTP port must be a number.");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("SFTP port must be between 1 and 65535.");
            if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(params.get("known_hosts_file"))))
                throw new IllegalArgumentException("Choose an existing SSH known_hosts file containing this server's verified host key.");
        }
        return params;
    }

    @Override
    public String toString() {
        return label;
    }

    public record Field(String key, String label, String initial, boolean secret, boolean required) {
    }
}
