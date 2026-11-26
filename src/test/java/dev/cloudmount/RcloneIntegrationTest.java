package dev.cloudmount;

import dev.cloudmount.rclone.*;
import dev.cloudmount.config.*;
import dev.cloudmount.mount.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RcloneIntegrationTest {
    @TempDir Path temp;
    @Test void realRcloneRendersCustomOAuthFailureAndEscapesProviderText() throws Exception {
        Path binary = Path.of("tools/rclone").toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary), "Requires tools/rclone");
        try (var probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            try { probe.bind(new InetSocketAddress("127.0.0.1", 53682)); }
            catch (java.io.IOException e) { Assumptions.abort("OAuth port is in use; do not interfere with a user's sign-in"); }
        }
        Path page = temp.resolve("oauth.html");
        try (var resource = getClass().getResourceAsStream("/dev/cloudmount/oauth-response.html")) {
            Files.copy(java.util.Objects.requireNonNull(resource), page);
        }
        var runner = new CommandRunner();
        Process process = runner.start(List.of(binary.toString(), "authorize", "drive", "--auth-no-open-browser", "--template", page.toString(),
                "--config", temp.resolve("test.conf").toString()), Map.of("RCLONE_DRIVE_CLIENT_ID", "synthetic.apps.googleusercontent.com", "RCLONE_DRIVE_CLIENT_SECRET", "synthetic-only"));
        Thread drain = Thread.ofVirtual().start(() -> { try { process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) {} });
        try {
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            java.net.http.HttpResponse<String> response = null;
            long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:53682/?error=access_denied&error_description=%3Cscript%3Esynthetic%3C%2Fscript%3E")).timeout(Duration.ofSeconds(3)).GET().build();
            while (response == null && System.nanoTime() < end) {
                try { response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()); }
                catch (java.io.IOException e) { Thread.sleep(100); }
            }
            assertNotNull(response); assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("Sign-in didn’t finish"));
            assertTrue(response.body().contains("CloudMount"));
            assertTrue(response.body().contains("&lt;script&gt;synthetic&lt;/script&gt;"));
            assertFalse(response.body().contains("<script>synthetic</script>"));
            assertFalse(response.body().contains("All done. Please go back to rclone."));
        } finally { process.destroy(); process.waitFor(5, TimeUnit.SECONDS); drain.join(1000); }
    }
    @Test void realFuseMountReadsWritesAndUnmountsOnlyOwnedFolder() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cloudmount.test.fuse"), "Enable with -Dcloudmount.test.fuse=true on a FUSE-capable host");
        assertTrue(Files.exists(Path.of("/dev/fuse")), "FUSE device is available");
        Path binary = Path.of("tools/rclone").toAbsolutePath();
        Path remote = Files.createDirectories(temp.resolve("remote"));
        Files.writeString(remote.resolve("existing.txt"), "existing file");
        AppPaths paths = new AppPaths(temp.resolve("config/cloudmount"), temp.resolve("cache/cloudmount"), temp.resolve("state/cloudmount"));
        PrivateFiles.write(paths.credentials(), "[cloudmount]\ntype = alias\ncloudmount_cache_id = " + UUID.randomUUID() + "\nremote = " + remote + "\n");
        MountService service = new MountService(paths, new CommandRunner(), line -> {});
        Path target = temp.resolve("mount folder");
        MountSettings settings = new MountSettings(binary.toString(), target.toString(), paths.cacheDir().toString(), 1, 1, 1);
        new SettingsStore(paths).save(settings);
        try {
            var launcher = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", System.getProperty("java.class.path"), "dev.cloudmount.MountWorker", "mount", temp.toString())
                    .redirectErrorStream(true).start();
            assertTrue(launcher.waitFor(60, TimeUnit.SECONDS), "mount-creator JVM exited");
            assertEquals(0, launcher.exitValue(), new String(launcher.getInputStream().readAllBytes()));
            assertTrue(MountTable.rcloneMounted(target));
            assertTrue(service.restore(), "new controller reattaches after creator JVM exited");
            assertTrue(service.running());
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.stateDir().resolve("mount-session.json"))));
            assertTrue(service.storage().known());
            assertTrue(Files.getFileStore(target).getTotalSpace() > 0, "FUSE statfs reports total storage to Linux");
            assertTrue(Files.getFileStore(target).getUsableSpace() >= 0, "FUSE reports free storage");
            assertEquals("existing file", Files.readString(target.resolve("existing.txt")));
            try (var open = java.nio.channels.FileChannel.open(target.resolve("still-open.txt"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                open.write(java.nio.ByteBuffer.wrap("pending write".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertThrows(Exception.class, service::unmount, "Must refuse a busy mount or pending upload");
                assertTrue(MountTable.rcloneMounted(target));
            }
            Files.writeString(target.resolve("uploaded.txt"), "written through FUSE");
            long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < end && (!Files.exists(remote.resolve("uploaded.txt")) || service.snapshot().hasPendingWrites())) Thread.sleep(200);
            assertEquals("written through FUSE", Files.readString(remote.resolve("uploaded.txt")));
            assertFalse(service.snapshot().hasPendingWrites());
            service.unmount();
            assertFalse(MountTable.mounted(target));
            assertFalse(service.ownsMount());
            assertEquals("existing file", Files.readString(remote.resolve("existing.txt")));
            assertFalse(Files.exists(paths.stateDir().resolve("mount-session.json")));
        } finally {
            if (service.ownsMount()) {
                // This fixture owns only this temporary mount; never detach any other mount.
                new ProcessBuilder("fusermount3", "-u", target.toString()).start().waitFor(5, TimeUnit.SECONDS);
                service.unmount();
            }
        }
    }
    @Test void realRcloneAcceptsAuthenticatedRcAndRejectsWrongPassword() throws Exception {
        Path binary = Path.of("tools/rclone").toAbsolutePath();
        Assumptions.assumeTrue(Files.isExecutable(binary), "Optional real-engine test requires tools/rclone");
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        var runner = new CommandRunner();
        Process process = runner.start(List.of(binary.toString(), "rcd", "--rc-addr", "127.0.0.1:" + port,
                "--config", temp.resolve("rclone.conf").toString()), Map.of("RCLONE_RC_USER", "cloudmount", "RCLONE_RC_PASS", "synthetic-password"));
        Thread drain = Thread.ofVirtual().start(() -> { try { process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) {} });
        try {
            RcClient client = new RcClient(port, "synthetic-password");
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean ready = false;
            while (System.nanoTime() < end) {
                try { assertEquals(process.pid(), client.call("core/pid").path("pid").asLong()); ready = true; break; }
                catch (java.io.IOException e) { Thread.sleep(100); }
            }
            assertTrue(ready, "real rclone control API became ready");
            assertThrows(Exception.class, () -> new RcClient(port, "incorrect-password").call("config/listremotes"));
            assertTrue(client.call("config/listremotes").has("remotes"));
            assertTrue(client.call("core/stats").has("bytes"));
        } finally { process.destroy(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); drain.join(1000); }
    }
}
