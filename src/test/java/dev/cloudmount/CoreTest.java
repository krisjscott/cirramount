package dev.cloudmount;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudmount.account.GoogleDriveService;
import dev.cloudmount.config.*;
import dev.cloudmount.mount.*;
import dev.cloudmount.rclone.CommandRunner;
import dev.cloudmount.transfer.TransferSnapshot;
import dev.cloudmount.transfer.StorageSnapshot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();

    @Test void settingsRoundTripAndPrivatePermissions() throws Exception {
        AppPaths paths = paths();
        var store = new SettingsStore(paths);
        var expected = new MountSettings("rclone", temp.resolve("my drive").toString(), temp.resolve("cache").toString(), 30, 48, 10);
        store.save(expected);
        assertEquals(expected, store.load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.settings())));
        assertFalse(Files.readString(paths.settings()).contains("secret"));
    }
    @Test void rejectsNestedCacheAndUnsafeSettings() {
        assertThrows(IllegalArgumentException.class, () -> MountService.validatePaths(temp.resolve("drive"), temp.resolve("drive/cache")));
        assertThrows(IllegalArgumentException.class, () -> MountService.validatePaths(temp.resolve("cache/drive"), temp.resolve("cache")));
        assertThrows(IllegalArgumentException.class, () -> new MountSettings("rclone", "relative", "/cache", 20, 72, 5).validate());
        assertThrows(IllegalArgumentException.class, () -> new MountSettings("rclone", "/drive", "/cache", 0, 72, 5).validate());
    }
    @Test void honorsOnlyAbsoluteXdgDirectories() {
        var result = AppPaths.from(Map.of("XDG_CONFIG_HOME", "relative", "XDG_CACHE_HOME", "/var/tmp/cache"), temp);
        assertEquals(temp.resolve(".config/cloudmount"), result.configDir());
        assertEquals(Path.of("/var/tmp/cache/cloudmount"), result.cacheDir());
    }
    @Test void mountTableDecodesSpacesAndDistinguishesOtherFilesystems() {
        String table = "66 1 0:50 / /tmp/my\\040drive rw - fuse.rclone cloudmount: rw\n"
                + "67 1 0:51 / /tmp/other rw - tmpfs tmpfs rw\n";
        assertTrue(MountTable.contains(table, Path.of("/tmp/my drive"), true));
        assertTrue(MountTable.contains(table, Path.of("/tmp/other"), false));
        assertFalse(MountTable.contains(table, Path.of("/tmp/other"), true));
    }
    @Test void pendingUploadsPreventSafeStopEvenWithoutActiveTransfers() throws Exception {
        var s = TransferSnapshot.parse(json.readTree("{\"bytes\":1024,\"speed\":0}"),
                json.readTree("{\"diskCache\":{\"uploadsQueued\":2,\"bytesUsed\":4096}}"),
                json.readTree("{\"queue\":[{\"name\":\"notes.txt\",\"size\":1024}]}"));
        assertTrue(s.hasPendingWrites()); assertEquals(2, s.queued()); assertEquals("Upload pending", s.items().getFirst().status());
    }
    @Test void errorsAndFullDiskAlsoPreventSafeStop() throws Exception {
        for (String disk : List.of("{\"erroredFiles\":1}", "{\"outOfSpace\":true}")) {
            var s = TransferSnapshot.parse(json.readTree("{}"), json.readTree("{\"diskCache\":" + disk + "}"), json.readTree("{}"));
            assertTrue(s.hasPendingWrites());
        }
    }
    @Test void combinesActiveTransfersAndQueueWithoutDuplicatingFiles() throws Exception {
        var s = TransferSnapshot.parse(json.readTree("{\"transferring\":[{\"name\":\"one.txt\",\"size\":100,\"bytes\":50}]}"),
                json.readTree("{\"diskCache\":{\"uploadsInProgress\":1}}"),
                json.readTree("{\"queue\":[{\"name\":\"one.txt\",\"uploading\":true}]}"));
        assertEquals(1, s.items().size()); assertEquals(.5, s.items().getFirst().progress());
        assertTrue(s.hasPendingWrites());
    }
    @Test void mountCommandKeepsPathsAsSingleArgumentsAndApiPrivate() {
        var s = new MountSettings("rclone", "/tmp/my drive", "/tmp/cache directory", 20, 72, 5);
        var command = MountService.command(s, Path.of("/tmp/config file"), Path.of(s.mountPath()), 12345);
        assertTrue(command.contains("/tmp/my drive")); assertTrue(command.contains("/tmp/cache directory"));
        assertTrue(command.contains("127.0.0.1:12345")); assertFalse(command.contains("--rc-no-auth"));
        assertFalse(command.contains("--allow-other")); assertFalse(command.contains("--daemon"));
    }
    @Test void storageBarUsesQuotaAndDoesNotInventMissingCapacity() throws Exception {
        var quota = StorageSnapshot.parse(json.readTree("{\"total\":1000,\"used\":200,\"free\":700}"));
        assertTrue(quota.known()); assertEquals(.3, quota.fraction(), .001);
        assertFalse(StorageSnapshot.parse(json.readTree("{}")).known());
        assertFalse(StorageSnapshot.parse(json.readTree("{\"total\":0,\"free\":0}")).known());
    }
    @Test void staleSessionCannotStopReusedProcessId() throws Exception {
        Process unrelated = new CommandRunner().start(List.of("sleep", "30"), Map.of());
        try {
            MountSettings s = new MountSettings("rclone", temp.resolve("mount").toString(), temp.resolve("cache").toString(), 20, 72, 5);
            MountSession stale = new MountSession(1, s, s.mountPath(), "CloudMount-" + UUID.randomUUID(), 12345, "synthetic-long-password-for-test-only", unrelated.pid(), 1);
            PrivateFiles.write(paths().stateDir().resolve("mount-session.json"), json.writeValueAsString(stale));
            assertFalse(new MountService(paths(), new CommandRunner(), line -> {}).restore());
            assertTrue(unrelated.isAlive());
        } finally { unrelated.destroy(); unrelated.waitFor(); }
    }
    @Test void unfinishedLaunchRetainsRecoveryInformation() throws Exception {
        var s = new MountSettings("rclone", temp.resolve("mount").toString(), temp.resolve("cache").toString(), 20, 72, 5);
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        var starting = new MountSession(1, s, s.mountPath(), "CloudMount-" + UUID.randomUUID(), unusedPort, "synthetic-long-password-for-test-only", 0, 0);
        Path file = paths().stateDir().resolve("mount-session.json");
        PrivateFiles.write(file, json.writeValueAsString(starting));
        var service = new MountService(paths(), new CommandRunner(), line -> {});
        assertTrue(service.restore());
        assertThrows(Exception.class, service::unmount);
        assertTrue(Files.exists(file));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 120000));
        service.unmount();
        assertFalse(Files.exists(file));
    }
    @Test void tokenExtractionIgnoresProgressAndRejectsIncompleteTokens() throws Exception {
        String token = GoogleDriveService.extractToken("progress\n{\"access_token\":\"test-only\",\"refresh_token\":\"test-only-refresh\"}\nEnd");
        assertEquals("test-only", json.readTree(token).path("access_token").asText());
        assertThrows(Exception.class, () -> GoogleDriveService.extractToken("{\"access_token\":\"incomplete\"}"));
    }
    @Test void commandTimeoutStopsOwnedChild() throws Exception {
        var runner = new CommandRunner();
        Process child = runner.start(List.of("sleep", "20"), Map.of());
        assertThrows(Exception.class, () -> runner.collect(child, Duration.ofMillis(100), line -> {}));
        assertFalse(child.isAlive());
    }
    @Test void redactsCredentialsAndBrowserState() {
        String output = CommandRunner.redact("{\"refresh_token\":\"private-value\",\"client_secret\":\"also-private\"} http://127.0.0.1:53682/auth?state=private");
        assertFalse(output.contains("private-value")); assertFalse(output.contains("also-private")); assertFalse(output.contains("state=private"));
    }
    @Test void loginSavesOnlyVerifiedAccountAndPreservesExistingAccountOnFailure() throws Exception {
        Path fake = temp.resolve("fake-rclone");
        Files.writeString(fake, """
                #!/usr/bin/env python3
                import json,sys,os
                if sys.argv[1]=='authorize':
                    assert '--template' in sys.argv
                    template = sys.argv[sys.argv.index('--template')+1]
                    assert 'Return to CirraMount' in open(template).read()
                    assert os.environ['RCLONE_DRIVE_CLIENT_SECRET']=='test-secret'
                    assert 'test-secret' not in sys.argv
                    print('http://127.0.0.1:53682/auth?state=synthetic')
                    print(json.dumps({'access_token':'synthetic-access','refresh_token':'synthetic-refresh'}))
                elif sys.argv[1]=='about':
                    print(json.dumps({'used':2048}))
                """);
        fake.toFile().setExecutable(true);
        var service = new GoogleDriveService(paths(), new CommandRunner());
        List<String> urls = new ArrayList<>();
        service.login(fake.toString(), "test.apps.googleusercontent.com", "test-secret", uri -> urls.add(uri.toString()));
        assertTrue(service.hasAccount()); assertEquals(1, urls.size());
        String previous = Files.readString(paths().credentials());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths().credentials())));
        Files.writeString(fake, Files.readString(fake).replace("print(json.dumps({'used':2048}))", "sys.exit(3)"));
        assertThrows(Exception.class, () -> service.login(fake.toString(), "test.apps.googleusercontent.com", "test-secret", uri -> {}));
        assertEquals(previous, Files.readString(paths().credentials()));
        Files.writeString(fake, "#!/bin/sh\nexit 1\n");
        assertThrows(Exception.class, () -> service.login(fake.toString(), "test.apps.googleusercontent.com", "test-secret", uri -> {}));
        assertEquals(previous, Files.readString(paths().credentials()));
    }
    private AppPaths paths() { return new AppPaths(temp.resolve("config"), temp.resolve("cache"), temp.resolve("state")); }
}
