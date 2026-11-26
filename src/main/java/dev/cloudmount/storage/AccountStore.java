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
}
