package dev.cloudmount.config;

import java.nio.file.Path;
import java.util.Map;

public record AppPaths(Path configDir, Path cacheDir, Path stateDir) {
    public static AppPaths system() {
        return from(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    public static AppPaths from(Map<String, String> env, Path home) {
        return new AppPaths(xdg(env, "XDG_CONFIG_HOME", home.resolve(".config")).resolve("cloudmount"),
                xdg(env, "XDG_CACHE_HOME", home.resolve(".cache")).resolve("cloudmount"),
                xdg(env, "XDG_STATE_HOME", home.resolve(".local/state")).resolve("cloudmount"));
    }

    private static Path xdg(Map<String, String> env, String key, Path fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank() || !Path.of(raw).isAbsolute()) return fallback;
        return Path.of(raw);
    }

    public Path credentials() {
        return configDir.resolve("rclone.conf");
    }

    public Path settings() {
        return configDir.resolve("settings.json");
    }
}
