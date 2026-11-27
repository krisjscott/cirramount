package dev.cloudmount;

import dev.cloudmount.config.*;
import dev.cloudmount.storage.*;
import dev.cloudmount.mount.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiAccountTest {
    @TempDir Path temp;
    AppPaths root(){return new AppPaths(temp.resolve("config"),temp.resolve("cache"),temp.resolve("state"));}
    @Test void multipleAccountsHaveIndependentCredentialsSettingsAndState() throws Exception {
        var store=new AccountStore(root());
        var first=store.add("Personal",Provider.GOOGLE,"");var second=store.add("Work",Provider.GOOGLE,"");
        assertNotEquals(first.id(),second.id());assertEquals(2,store.list().size());
        assertNotEquals(store.paths(first).credentials(),store.paths(second).credentials());
        assertNotEquals(store.paths(first).stateDir(),store.paths(second).stateDir());
        assertNotEquals(new SettingsStore(store.paths(first)).load().mountPath(),new SettingsStore(store.paths(second)).load().mountPath());
        PrivateFiles.write(store.paths(first).credentials(),"synthetic-private-token");
        assertFalse(Files.readString(store.paths(first).configDir().resolve("account.json")).contains("synthetic-private-token"));
        PrivateFiles.write(store.paths(first).stateDir().resolve("mount-session.json"),"{}");
        assertThrows(Exception.class,()->store.remove(first));
        assertTrue(Files.exists(store.paths(first).credentials()));
        store.remove(second);assertEquals(List.of(first),store.list());
    }
    @Test void legacyConnectionIsDiscoveredWithoutChangingCredentialsOrSession() throws Exception {
        PrivateFiles.write(root().credentials(),"legacy-token");
        Path state=root().stateDir().resolve("mount-session.json");PrivateFiles.write(state,"legacy-session");
        var store=new AccountStore(root());var legacy=store.list().getFirst();
        assertEquals("legacy",legacy.id());assertEquals(root(),store.paths(legacy));
        assertEquals("legacy-token",Files.readString(root().credentials()));assertEquals("legacy-session",Files.readString(state));
    }
    @Test void deletingLocalDataRemovesOnlySelectedAccountFilesAndDefaultCache() throws Exception {
        var store = new AccountStore(root());
        var first = store.add("Personal", Provider.GOOGLE, "");
        var second = store.add("Work", Provider.DROPBOX, "");
        Path firstCache = store.paths(first).cacheDir();
        Path secondCache = store.paths(second).cacheDir();
        PrivateFiles.write(firstCache.resolve("old-cache/file"), "pending");
        PrivateFiles.write(secondCache.resolve("file"), "keep");
        PrivateFiles.write(store.paths(first).credentials(), "token");
        PrivateFiles.write(store.paths(first).stateDir().resolve("rclone.log"), "log");
        PrivateFiles.write(store.paths(first).stateDir().resolve("mount-session.json"), "{}");
        assertThrows(Exception.class, () -> store.removeWithLocalData(first));
        assertTrue(Files.exists(firstCache.resolve("old-cache/file")));
        Files.delete(store.paths(first).stateDir().resolve("mount-session.json"));

        store.removeWithLocalData(first);
        assertEquals(List.of(second), store.list());
        assertFalse(Files.exists(store.paths(first).configDir()));
        assertFalse(Files.exists(store.paths(first).stateDir()));
        assertFalse(Files.exists(firstCache));
        assertEquals("keep", Files.readString(secondCache.resolve("file")));
        assertTrue(Files.exists(store.paths(second).settings()));
    }
    @Test void customCacheDeletesOnlyCurrentNamespaceAndKeepsOtherFiles() throws Exception {
        var store = new AccountStore(root());
        var account = store.add("Custom", Provider.GOOGLE, "");
        Path custom = temp.resolve("shared-cache");
        String id = UUID.randomUUID().toString();
        new SettingsStore(store.paths(account)).save(settings(temp.resolve("mount"), custom));
        PrivateFiles.write(store.paths(account).credentials(), "[cloudmount]\ncloudmount_cache_id = " + id + "\n");
        PrivateFiles.write(custom.resolve(id).resolve("vfs/file"), "cache");
        PrivateFiles.write(custom.resolve("unrelated/file"), "keep");

        store.removeWithLocalData(account);
        assertFalse(Files.exists(custom.resolve(id)));
        assertEquals("keep", Files.readString(custom.resolve("unrelated/file")));
        assertTrue(store.list().isEmpty());
    }
    @Test void legacyCleanupKeepsSharedAppFilesAndOtherAccountCache() throws Exception {
        var store = new AccountStore(root());
        var other = store.add("Work", Provider.DROPBOX, "");
        String id = UUID.randomUUID().toString();
        PrivateFiles.write(root().credentials(), "[cloudmount]\ncloudmount_cache_id = " + id + "\n");
        PrivateFiles.write(root().cacheDir().resolve(id).resolve("vfs/file"), "legacy-cache");
        PrivateFiles.write(root().cacheDir().resolve("accounts").resolve(other.id()).resolve("file"), "other-cache");
        PrivateFiles.write(root().configDir().resolve("google-oauth.json"), "shared-client");
        var legacy = store.list().stream().filter(a -> a.id().equals("legacy")).findFirst().orElseThrow();

        store.removeWithLocalData(legacy);
        assertFalse(Files.exists(root().credentials()));
        assertFalse(Files.exists(root().cacheDir().resolve(id)));
        assertEquals("other-cache", Files.readString(root().cacheDir().resolve("accounts").resolve(other.id()).resolve("file")));
        assertEquals("shared-client", Files.readString(root().configDir().resolve("google-oauth.json")));
        assertEquals(List.of(other), store.list());
    }
    @Test void preventsCrossAccountMountAndCacheOverlapIncludingSymlinks() throws Exception {
        var store=new AccountStore(root());var a=store.add("A",Provider.GOOGLE,"");var b=store.add("B",Provider.DROPBOX,"");
        Path other=Files.createDirectories(temp.resolve("other"));
        new SettingsStore(store.paths(b)).save(settings(other,temp.resolve("cache-b")));
        var registry=new MountRegistry(store,line->{});
        assertThrows(Exception.class,()->registry.validateLocations(a,settings(other.resolve("nested"),temp.resolve("cache-a"))));
        Path link=temp.resolve("link");Files.createSymbolicLink(link,other);
        assertThrows(Exception.class,()->registry.validateLocations(a,settings(link,temp.resolve("cache-a"))));
        registry.validateLocations(a,settings(temp.resolve("a"),temp.resolve("cache-a")));
    }
    @Test void redactsAdditionalProviderSecretsAndPreservesPasswordWhitespace() {
        assertFalse(dev.cloudmount.rclone.CommandRunner.redact("secret_access_key = sensitive session_token: credential pass=private").contains("sensitive"));
        assertFalse(dev.cloudmount.rclone.CommandRunner.redact("session_token: credential").contains("credential"));
        assertEquals(" password ",Provider.WEBDAV.parameters(Map.of("url","https://example.test","vendor","other","pass"," password ")).get("pass"));
    }
    @Test void validatesProviderCredentialsAndRemotePaths() throws Exception {
        assertThrows(IllegalArgumentException.class,()->Provider.S3.parameters(Map.of("access_key_id","a")));
        assertEquals("true",Provider.S3.parameters(Map.of()).get("env_auth"));
        assertThrows(IllegalArgumentException.class,()->Provider.S3_COMPATIBLE.parameters(Map.of("endpoint","file:///tmp")));
        assertEquals("Other",Provider.S3_COMPATIBLE.parameters(Map.of("endpoint","https://s3.example.test")).get("provider"));
        assertThrows(IllegalArgumentException.class,()->new CloudAccount(UUID.randomUUID().toString(),"A",Provider.S3,""));
        assertThrows(IllegalArgumentException.class,()->new CloudAccount(UUID.randomUUID().toString(),"A",Provider.GOOGLE,"../private"));
        assertThrows(IllegalArgumentException.class,()->Provider.SFTP.parameters(Map.of("host","example.test","user","me","port","22","known_hosts_file",temp.resolve("missing").toString())));
    }

    @Test void twoRealFuseMountsRemainIndependentWhenOneIsUnmounted() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("cloudmount.test.fuse"));
        var store=new AccountStore(root());var a=store.add("First",Provider.GOOGLE,"");var b=store.add("Second",Provider.DROPBOX,"");
        for(var account:List.of(a,b)){
            Path remote=Files.createDirectories(temp.resolve(account.id()));Files.writeString(remote.resolve("identity.txt"),account.name());
            PrivateFiles.write(store.paths(account).credentials(),"[cloudmount]\ntype = alias\nremote = "+remote+"\ncloudmount_cache_id = "+UUID.randomUUID()+"\n");
            new SettingsStore(store.paths(account)).save(settings(temp.resolve("mount-"+account.id()),store.paths(account).cacheDir()));
        }
        var registry=new MountRegistry(store,line->{});
        try{
            registry.mount(a,new SettingsStore(store.paths(a)).load());registry.mount(b,new SettingsStore(store.paths(b)).load());
            assertEquals("First",Files.readString(registry.service(a).mountPath().resolve("identity.txt")));
            assertEquals("Second",Files.readString(registry.service(b).mountPath().resolve("identity.txt")));
            assertNotEquals(registry.service(a).mountPath(),registry.service(b).mountPath());
            registry.service(a).unmount();assertTrue(registry.service(b).running());assertTrue(MountTable.rcloneMounted(registry.service(b).mountPath()));
            var reopened=new MountRegistry(store,line->{});assertTrue(reopened.service(b).restore());
            assertEquals("Second",Files.readString(reopened.service(b).mountPath().resolve("identity.txt")));reopened.service(b).unmount();
        }finally{
            for(var account:List.of(a,b)){
                var service=registry.service(account);
                if(service.ownsMount())service.unmount();
            }
        }
    }
    MountSettings settings(Path mount,Path cache){return new MountSettings(Path.of("tools/rclone").toAbsolutePath().toString(),mount.toString(),cache.toString(),1,1,1);}
}
