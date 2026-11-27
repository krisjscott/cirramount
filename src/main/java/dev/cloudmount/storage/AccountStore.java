package dev.cloudmount.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudmount.config.*;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;

public final class AccountStore {
    private final AppPaths root;
    private final ObjectMapper json = new ObjectMapper();

    public AccountStore(AppPaths root) {
        this.root = root;
    }

    public AppPaths root() {
        return root;
    }

    public AppPaths paths(CloudAccount account) {
        if (account.id().equals("legacy")) return root;
        return new AppPaths(root.configDir().resolve("accounts").resolve(account.id()), root.cacheDir().resolve("accounts").resolve(account.id()), root.stateDir().resolve("accounts").resolve(account.id()));
    }

    private Path descriptor(CloudAccount a) {
        return a.id().equals("legacy") ? root.configDir().resolve("legacy-account.json") : paths(a).configDir().resolve("account.json");
    }

    public synchronized List<CloudAccount> list() throws IOException {
        var result = new ArrayList<CloudAccount>();
        Path legacy = root.configDir().resolve("legacy-account.json");
        if (!Files.exists(root.configDir().resolve("legacy-removed")) && (Files.exists(root.credentials()) || Files.exists(root.stateDir().resolve("mount-session.json")))) {
            CloudAccount account = Files.exists(legacy) ? json.readValue(legacy.toFile(), CloudAccount.class) : new CloudAccount("legacy", "Google Drive", Provider.GOOGLE, "");
            result.add(account);
        }
        Path accounts = root.configDir().resolve("accounts");
        if (Files.isDirectory(accounts)) try (var dirs = Files.list(accounts)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path profile = dir.resolve("account.json");
                if (!Files.exists(profile)) continue;
                CloudAccount a = json.readValue(profile.toFile(), CloudAccount.class);
                if (!dir.getFileName().toString().equals(a.id()) || a.id().equals("legacy"))
                    throw new IOException("Connection directory does not match its ID.");
                result.add(a);
            }
        }
        result.sort(Comparator.comparing(CloudAccount::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public synchronized CloudAccount add(String name, Provider provider, String remotePath) throws IOException {
        CloudAccount a = new CloudAccount(UUID.randomUUID().toString(), name.strip(), provider, remotePath);
        var scoped = paths(a);
        String leaf = name.replaceAll("[^a-zA-Z0-9_-]", "-") + "-" + a.id().substring(0, 6);
        MountSettings defaults = MountSettings.defaults(scoped);
        new SettingsStore(scoped).save(new MountSettings(defaults.rcloneBinary(), Path.of(System.getProperty("user.home"), "Cloud", leaf).toString(), scoped.cacheDir().toString(), 20, 72, 5));
        save(a);
        return a;
    }

    public synchronized void save(CloudAccount a) throws IOException {
        PrivateFiles.write(descriptor(a), json.writerWithDefaultPrettyPrinter().writeValueAsString(a));
    }

    public synchronized void remove(CloudAccount a) throws IOException {
        if (Files.exists(paths(a).stateDir().resolve("mount-session.json")))
            throw new IOException("Unmount this connection before removing it.");
        if (a.id().equals("legacy")) PrivateFiles.write(root.configDir().resolve("legacy-removed"), "removed\n");
        Files.deleteIfExists(paths(a).credentials());
        Files.deleteIfExists(descriptor(a));
    }

    /** Removes this profile and its identifiable local data. Provider files are never touched. */
    public synchronized void removeWithLocalData(CloudAccount a) throws IOException {
        AppPaths scoped = paths(a);
        if (Files.exists(scoped.stateDir().resolve("mount-session.json")))
            throw new IOException("Unmount this connection before deleting its local data.");

        MountSettings settings = new SettingsStore(scoped).load();
        Path chosenCache = Path.of(settings.cachePath()).toAbsolutePath().normalize();
        Path privateCache = scoped.cacheDir().toAbsolutePath().normalize();
        if (chosenCache.equals(privateCache) && !a.id().equals("legacy")) {
            deleteTree(privateCache);
        } else if (Files.exists(scoped.credentials())) {
            // A custom cache base may contain unrelated files. Only the UUID namespace
            // created for this connection is owned by CloudMount.
            String id = Files.readString(scoped.credentials()).lines()
                    .filter(line -> line.startsWith("cloudmount_cache_id = "))
                    .map(line -> line.substring("cloudmount_cache_id = ".length()).strip())
                    .findFirst().orElse("");
            try {
                UUID.fromString(id);
                deleteTree(chosenCache.resolve(id));
            } catch (IllegalArgumentException ignored) {
                // Older credentials have no trustworthy cache namespace.
            }
        }

        if (a.id().equals("legacy")) {
            PrivateFiles.write(root.configDir().resolve("legacy-removed"), "removed\n");
            Files.deleteIfExists(scoped.credentials());
            Files.deleteIfExists(scoped.settings());
            Files.deleteIfExists(scoped.stateDir().resolve("rclone.log"));
            Files.deleteIfExists(descriptor(a));
        } else {
            deleteTree(scoped.stateDir());
            deleteTree(scoped.configDir());
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
                Files.delete(entry);
        }
    }
}
