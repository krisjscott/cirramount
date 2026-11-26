package dev.cloudmount.mount;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudmount.config.*;
import dev.cloudmount.rclone.*;
import dev.cloudmount.transfer.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

// Mounts are independent rclone daemons. The window only controls them.

public final class MountService {
    private final AppPaths paths;
    private final CommandRunner runner;
    private final Consumer<String> log;
    private final ObjectMapper json = new ObjectMapper();
    private final String remote, volumeName;
    private volatile MountSession session;
    private RcClient rc;

    public MountService(AppPaths paths, CommandRunner runner, Consumer<String> log) {
        this(paths, runner, log, "cloudmount:", "GoogleDrive");
    }

    public MountService(AppPaths paths, CommandRunner runner, Consumer<String> log, String remote, String volumeName) {
        this.paths = paths;
        this.runner = runner;
        this.log = log;
        if (!remote.startsWith("cloudmount:") || remote.matches("(?s).*\\p{Cntrl}.*"))
            throw new IllegalArgumentException("Invalid remote path.");
        this.remote = remote;
        this.volumeName = volumeName;
    }

    private static void validateSession(MountSession saved) throws IOException {
        try {
            if (saved.version() != 1 || saved.settings() == null || !Path.of(saved.mountPath()).isAbsolute()
                    || !saved.deviceName().matches("CloudMount-[0-9a-f-]{36}") || saved.port() < 1
                    || saved.port() > 65535 || saved.password().length() < 32) throw new IllegalArgumentException();
            saved.settings().validate();
        } catch (RuntimeException e) {
            throw new IOException("Saved background mount information is invalid. Keep the cache and inspect the mount before starting another.", e);
        }
    }

    private static String resolveBinary(String binary) throws IOException {
        if (binary.contains("/")) {
            Path resolved = Path.of(binary).toRealPath();
            if (Files.isExecutable(resolved)) return resolved.toString();
        } else {
            for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
                Path candidate = Path.of(directory).resolve(binary);
                if (Files.isExecutable(candidate) && Files.isRegularFile(candidate))
                    return candidate.toRealPath().toString();
            }
        }
        throw new IOException("Choose an executable rclone binary.");
    }

    private static boolean processMatches(MountSession state) {
        if (state.pid() <= 0 || state.processStartedAt() <= 0) return false;
        if (isZombie(state.pid())) return false;
        return ProcessHandle.of(state.pid()).filter(ProcessHandle::isAlive)
                .flatMap(p -> p.info().startInstant()).map(t -> t.toEpochMilli() == state.processStartedAt()).orElse(false);
    }

    public static void validatePaths(Path mount, Path cache) {
        if (mount.getParent() == null || mount.equals(Path.of(System.getProperty("user.home"))))
            throw new IllegalArgumentException("Choose a dedicated empty mount folder.");
        if (cache.startsWith(mount) || mount.startsWith(cache))
            throw new IllegalArgumentException("Mount and cache folders must be separate, with neither inside the other.");
    }

    public static List<String> command(MountSettings s, Path config, Path mount, int port) {
        return List.of(s.rcloneBinary(), "mount", "cloudmount:", mount.toString(), "--config", config.toString(),
                "--cache-dir", s.cachePath(), "--vfs-cache-mode", "full", "--vfs-cache-max-size", s.cacheGiB() + "G",
                "--vfs-cache-max-age", s.maxAgeHours() + "h", "--vfs-write-back", s.writeBackSeconds() + "s",
                "--vfs-cache-min-free-space", "1G", "--dir-cache-time", "5m", "--poll-interval", "30s",
                "--umask", "077", "--rc", "--rc-addr", "127.0.0.1:" + port, "--log-level", "NOTICE",
                "--volname", "GoogleDrive", "--attr-timeout", "1s");
    }

    private static boolean isZombie(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            return stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z ");
        } catch (IOException e) {
            return false;
        }
    }

    private Path sessionFile() {
        return paths.stateDir().resolve("mount-session.json");
    }

    private Path logFile() {
        return paths.stateDir().resolve("rclone.log");
    }

    public boolean running() {
        var saved = session;
        return saved != null && processMatches(saved);
    }

    public boolean ownsMount() {
        return session != null || Files.exists(sessionFile());
    }

    public Path mountPath() {
        var saved = session;
        return saved == null ? null : Path.of(saved.mountPath());
    }

    public MountSettings activeSettings() {
        var saved = session;
        return saved == null ? null : saved.settings();
    }

    public synchronized boolean restore() throws Exception {
        if (session != null) return true;
        if (!Files.exists(sessionFile())) return false;
        MountSession saved = json.readValue(sessionFile().toFile(), MountSession.class);
        validateSession(saved);
        Path target = Path.of(saved.mountPath());
        if (!MountTable.mounted(target) && !processMatches(saved) && !starting(saved)) {
            Files.delete(sessionFile());
            log.accept("The previous mount has stopped. Cache retained.");
            return false;
        }
        if (MountTable.mounted(target) && MountTable.owns(target, saved.deviceName()))
            throw new IOException("Another filesystem now uses the saved mount folder. CloudMount will not control it.");
        session = saved;
        rc = new RcClient(saved.port(), saved.password());
        if (processMatches(saved) || saved.pid() == 0) {
            try {
                bindProcess();
            } catch (Exception e) {
                log.accept("Background mount found, but its status is unavailable. Cache retained.");
            }
        }
        log.accept("Reconnected to background mount at " + target);
        return true;
    }

    public synchronized void mount(MountSettings settings) throws Exception {
        settings.validate();
        if (restore()) throw new IOException("Unmount the current background drive first.");
        if (!Files.exists(Path.of("/dev/fuse")))
            throw new IOException("FUSE is unavailable: /dev/fuse is missing. Enable FUSE on this Linux host.");
        Path target = Path.of(settings.mountPath()).normalize(), cache = Path.of(settings.cachePath()).normalize();
        Files.createDirectories(target);
        Files.createDirectories(cache);
        target = target.toRealPath();
        cache = cache.toRealPath();
        validatePaths(target, cache);
        if (MountTable.mounted(target))
            throw new IOException("That folder is already mounted. If using the older CloudMount, unmount there before switching to background mode.");
        try (var entries = Files.list(target)) {
            if (entries.findAny().isPresent())
                throw new IOException("The mount folder must be empty; existing files will not be hidden.");
        }
        if (!Files.isWritable(target) || !Files.isWritable(cache))
            throw new IOException("Mount and cache folders must be writable.");
        String cacheId = Files.readString(paths.credentials()).lines().filter(line -> line.startsWith("cloudmount_cache_id = "))
                .map(line -> line.substring("cloudmount_cache_id = ".length()).strip()).findFirst()
                .orElseThrow(() -> new IOException("Sign in again to initialize this account's cache identity."));
        UUID.fromString(cacheId);
        Path accountCache = cache.resolve(cacheId);
        Files.createDirectories(accountCache);
        MountSettings canonical = new MountSettings(resolveBinary(settings.rcloneBinary()), target.toString(), cache.toString(),
                settings.cacheGiB(), settings.maxAgeHours(), settings.writeBackSeconds());
        MountSettings effective = new MountSettings(canonical.rcloneBinary(), canonical.mountPath(), accountCache.toString(),
                settings.cacheGiB(), settings.maxAgeHours(), settings.writeBackSeconds());
        int port;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        String password = UUID.randomUUID().toString() + UUID.randomUUID();
        session = new MountSession(1, canonical, target.toString(), "CloudMount-" + UUID.randomUUID(), port, password, 0, 0);
        rc = new RcClient(port, password);
        saveSession();
        PrivateFiles.write(logFile(), "");
        var command = new ArrayList<>(command(effective, paths.credentials(), target, port));
        command.set(2, remote);
        command.set(command.indexOf("--volname") + 1, volumeName);
        command.addAll(List.of("--daemon", "--daemon-wait", "40s", "--devname", session.deviceName(),
                "--log-file", logFile().toString(), "--log-file-max-size", "2M", "--log-file-max-backups", "1"));
        try {
            var launched = runner.run(command, Map.of("RCLONE_RC_USER", "cloudmount", "RCLONE_RC_PASS", password), Duration.ofSeconds(55));
            if (launched.exitCode() != 0) throw new IOException("Background mount failed. " + tailLog());
            if (MountTable.owns(target, session.deviceName()))
                throw new IOException("The background mount did not become ready. " + tailLog());
            bindProcess();
            log.accept("Storage mounted in the background at " + target + ". You can close the window.");
        } catch (Exception failure) {
            // Keep any mount that started and its recovery descriptor; never discard pending writes.
            if (!MountTable.mounted(target)) clear();
            throw failure;
        }
    }

    private void bindProcess() throws Exception {
        long pid = rc.call("core/pid").path("pid").asLong(-1);
        if (session.pid() != 0 && session.pid() != pid)
            throw new IOException("The control API does not match the saved mount process.");
        ProcessHandle process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IOException("The background mount process is not running."));
        long started = process.info().startInstant().orElseThrow(() -> new IOException("Cannot verify the background process identity.")).toEpochMilli();
        if (session.processStartedAt() != 0 && session.processStartedAt() != started)
            throw new IOException("The saved process ID has been reused. CloudMount will not stop it.");
        MountSession resolved = session.withProcess(pid, started);
        if (!resolved.equals(session)) {
            session = resolved;
            saveSession();
        }
    }

    private boolean starting(MountSession state) throws IOException {
        return state.pid() == 0 && System.currentTimeMillis() - Files.getLastModifiedTime(sessionFile()).toMillis() < 70000;
    }

    public synchronized TransferSnapshot snapshot() throws Exception {
        ensureIdentity();
        if (MountTable.owns(mountPath(), session.deviceName()))
            throw new IOException("The drive is no longer mounted. Cached uploads may still be completing.");
        return readTransfers();
    }

    private TransferSnapshot readTransfers() throws Exception {
        return TransferSnapshot.parse(rc.call("core/stats"), rc.call("vfs/stats"), rc.call("vfs/queue"));
    }

    public synchronized StorageSnapshot storage() throws Exception {
        ensureIdentity();
        return StorageSnapshot.parse(rc.call("operations/about", json.writeValueAsString(Map.of("fs", remote))));
    }

    private void ensureIdentity() throws Exception {
        if (session == null) throw new IOException("No background drive is connected.");
        bindProcess();
        if (!processMatches(session))
            throw new IOException("The background mount has stopped. Cache retained. " + tailLog());
    }

    public synchronized void unmount() throws Exception {
        if (session == null && !restore()) return;
        Path target = mountPath();
        if (session.pid() == 0) {
            try {
                bindProcess();
            } catch (Exception e) {
                if (starting(session) || MountTable.mounted(target))
                    throw new IOException("The background mount is still starting or cannot be identified. Retry Unmount shortly; its recovery information has been kept.", e);
                clear();
                return;
            }
        }
        if (MountTable.mounted(target) && MountTable.owns(target, session.deviceName()))
            throw new IOException("Another filesystem is using that folder. It will not be unmounted.");
        if (running()) {
            ensureIdentity();
            if (readTransfers().hasPendingWrites())
                throw new IOException("Transfers or upload errors remain. Close files, wait for uploads, and retry Unmount. Your cache has been kept.");
        } else if (MountTable.mounted(target)) {
            throw new IOException("The engine stopped but its mount remains. Close files, then run fusermount3 -u on this mount folder. Keep the cache for recovery.");
        }
        if (MountTable.mounted(target)) {
            var result = runner.run(List.of("fusermount3", "-u", target.toString()), Map.of(), Duration.ofSeconds(10));
            if (result.exitCode() != 0)
                throw new IOException("The folder is busy. Close applications and terminals using it, then retry Unmount.");
        }
        if (processMatches(session)) ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroy);
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (processMatches(session) && !isZombie(session.pid()) && System.nanoTime() < end) Thread.sleep(100);
        if (MountTable.mounted(target)) throw new IOException("The mount is still attached. Retry Unmount.");
        if (processMatches(session) && !isZombie(session.pid()))
            throw new IOException("rclone is still stopping. Retry Unmount shortly.");
        clear();
        log.accept("Drive unmounted. Local cache retained.");
    }

    private void saveSession() throws IOException {
        PrivateFiles.write(sessionFile(), json.writerWithDefaultPrettyPrinter().writeValueAsString(session));
    }

    private void clear() throws IOException {
        Files.deleteIfExists(sessionFile());
        session = null;
        rc = null;
    }

    private String tailLog() {
        try {
            byte[] all = Files.readAllBytes(logFile());
            return CommandRunner.redact(new String(all, Math.max(0, all.length - 4096), Math.min(all.length, 4096), java.nio.charset.StandardCharsets.UTF_8)).strip();
        } catch (IOException e) {
            return "Check the mount log at " + logFile();
        }
    }
}
