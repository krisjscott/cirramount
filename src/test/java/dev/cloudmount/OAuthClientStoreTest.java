package dev.cloudmount;

import dev.cloudmount.account.OAuthClientStore;
import dev.cloudmount.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class OAuthClientStoreTest {
    @TempDir Path temp;
    AppPaths paths() { return new AppPaths(temp.resolve("config"), temp.resolve("cache"), temp.resolve("state")); }
    @Test void importsDesktopClientPrivatelyAndReloadsIt() throws Exception {
        Path downloaded = temp.resolve("download.json");
        Files.writeString(downloaded, "{\"installed\":{\"client_id\":\"example.apps.googleusercontent.com\",\"client_secret\":\"synthetic-secret\"}}");
        var store = new OAuthClientStore(paths()); store.importFile(downloaded);
        assertEquals("example.apps.googleusercontent.com", store.load().id());
        assertEquals("synthetic-secret", store.load().secret());
        assertFalse(store.load().toString().contains("synthetic-secret"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(paths().configDir().resolve("google-oauth.json"))));
    }
    @Test void invalidImportsPreservePreviousSetupAndDoNotEchoSecrets() throws Exception {
        var store = new OAuthClientStore(paths());
        Path source = temp.resolve("download.json");
        Files.writeString(source, "{\"installed\":{\"client_id\":\"example.apps.googleusercontent.com\",\"client_secret\":\"synthetic-secret\"}}");
        store.importFile(source);
        Files.writeString(source, "{\"web\":{\"client_secret\":\"private-value\"}}");
        var error = assertThrows(Exception.class, () -> store.importFile(source));
        assertFalse(error.getMessage().contains("private-value"));
        assertEquals("synthetic-secret", store.load().secret());
    }
    @Test void reusesConfiguredAccountClientWithoutImport() throws Exception {
        PrivateFiles.write(paths().credentials(), "[cloudmount]\nclient_id = saved.apps.googleusercontent.com\nclient_secret = saved-synthetic\n");
        assertEquals("saved.apps.googleusercontent.com", new OAuthClientStore(paths()).load().id());
    }
}
