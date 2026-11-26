package dev.cloudmount.storage;

import dev.cloudmount.config.*;
import dev.cloudmount.mount.MountService;
import dev.cloudmount.rclone.CommandRunner;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MountRegistry {
    private final AccountStore accounts;
    private final Map<String, MountService> mounts = new ConcurrentHashMap<>();
    private final Consumer<String> log;

    public MountRegistry(AccountStore accounts, Consumer<String> log) {
        this.accounts = accounts;
        this.log = log;
    }

    private static boolean overlaps(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    private static Path canonical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (Files.exists(path)) return path.toRealPath();
        Path parent = path.getParent();
        return parent == null ? path : canonical(parent).resolve(path.getFileName());
    }

    public MountService service(CloudAccount a) {
        return mounts.computeIfAbsent(a.id(), id -> new MountService(accounts.paths(a), new CommandRunner(), line -> log.accept(a.name() + ": " + line), a.remote(), a.name()));
    }

    public synchronized void mount(CloudAccount a, MountSettings settings) throws Exception {
        validateLocations(a, settings);
        service(a).mount(settings);
    }

    public void validateLocations(CloudAccount selected, MountSettings settings) throws Exception {
        settings.validate();
        Path target = canonical(Path.of(settings.mountPath())), cache = canonical(Path.of(settings.cachePath()));
        MountService.validatePaths(target, cache);
        for (CloudAccount other : accounts.list()) {
            if (other.id().equals(selected.id())) continue;
            MountService service = service(other);
            MountSettings saved = service.activeSettings();
            if (saved == null) saved = new SettingsStore(accounts.paths(other)).load();
            Path otherMount = canonical(Path.of(saved.mountPath())), otherCache = canonical(Path.of(saved.cachePath()));
            if (overlaps(target, otherMount) || overlaps(cache, otherMount) || overlaps(target, otherCache))
                throw new IOException("Mount/cache folders overlap with connection '" + other.name() + "'. Choose separate folders.");
        }
    }
}
