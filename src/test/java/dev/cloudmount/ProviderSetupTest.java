package dev.cloudmount;

import dev.cloudmount.config.*;
import dev.cloudmount.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderSetupTest {
    @TempDir Path temp;
    String binary(){return Path.of("tools/rclone").toAbsolutePath().toString();}
    AppPaths paths(){return new AppPaths(temp.resolve("config"),temp.resolve("cache"),temp.resolve("state"));}
    @Test void realConfigurationCommitsOnlyAfterSuccessfulRead() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));Files.createDirectories(data.resolve("folder"));
        var setup=new ProviderSetup();
        setup.configureBackend("alias","cloudmount:",paths(),binary(),Map.of("remote",data.toString()),q->{fail("Unexpected question: "+q.name());return null;},u->fail("No OAuth expected"));
        String saved=Files.readString(paths().credentials());assertTrue(saved.contains("type = alias"));assertTrue(saved.contains("cloudmount_cache_id"));
        assertThrows(Exception.class,()->setup.configureBackend("alias","cloudmount:",paths(),binary(),Map.of("remote",temp.resolve("missing").toString()),q->null,u->{}));
        assertEquals(saved,Files.readString(paths().credentials()));
        try(var files=Files.list(paths().configDir())){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".setup-")));}
    }
    @Test void realWebdavConfigurationObscuresPasswordAndReadsProvider() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var auth=new AtomicReference<String>();var methods=new ArrayList<String>();
        server.createContext("/",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));methods.add(exchange.getRequestMethod());
            String xml="<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
            byte[] body=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(207,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var params=Provider.WEBDAV.parameters(Map.of("url","http://127.0.0.1:"+server.getAddress().getPort()+"/","vendor","other","user","test","pass","synthetic-pass"));
            new ProviderSetup().configureBackend("webdav","cloudmount:",paths(),binary(),params,q->null,u->{});
            String config=Files.readString(paths().credentials());assertFalse(config.contains("synthetic-pass"));assertTrue(config.contains("pass = "));assertTrue(methods.contains("PROPFIND"));
            assertEquals("Basic "+Base64.getEncoder().encodeToString("test:synthetic-pass".getBytes()),auth.get());
        }finally{server.stop(0);}
    }
    @Test void realS3CompatibleReadsSelectedBucketUsingItsOwnKeys() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var auth=new AtomicReference<String>();var requested=new AtomicReference<String>();
        server.createContext("/",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));requested.set(exchange.getRequestURI().getPath());
            String xml="<?xml version=\"1.0\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>test-bucket</Name><Prefix/><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated></ListBucketResult>";
            byte[] body=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var params=Provider.S3_COMPATIBLE.parameters(Map.of("endpoint","http://127.0.0.1:"+server.getAddress().getPort(),"access_key_id","synthetic-key","secret_access_key","synthetic-secret","region","us-east-1"));
            new ProviderSetup().configureBackend("s3","cloudmount:test-bucket",paths(),binary(),params,q->null,u->{});
            assertEquals("/test-bucket",requested.get());assertTrue(auth.get().contains("Credential=synthetic-key/"));
        }finally{server.stop(0);}
    }
    @Test @Timeout(25) void guidedGoogleSetupOpensLocalConsentAndPreservesCredentialsOnDenial() throws Exception { deniedFlow("drive"); }
    @Test @Timeout(25) void guidedOneDriveSetupSurfacesBrowserConsent() throws Exception { deniedFlow("onedrive"); }
    @Test @Timeout(25) void guidedDropboxSetupSurfacesBrowserConsent() throws Exception { deniedFlow("dropbox"); }
    private void deniedFlow(String backend) throws Exception {
        try(var probe=new java.net.ServerSocket()){
            probe.setReuseAddress(true);
            try{probe.bind(new java.net.InetSocketAddress("127.0.0.1",53682));}catch(java.io.IOException e){Assumptions.abort("OAuth port is in use");}
        }
        PrivateFiles.write(paths().credentials(),"previous-private-connection");
        var opened=new AtomicBoolean();
        assertThrows(Exception.class,()->new ProviderSetup().configureBackend(backend,"cloudmount:",paths(),binary(),Map.of("client_id","synthetic.apps.googleusercontent.com","client_secret","synthetic-only","scope","drive"),q->{fail("Unexpected question: "+q.name());return null;},uri->{
            opened.set(true);
            try{
                var request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:53682/?error=access_denied&error_description=synthetic-denial")).timeout(java.time.Duration.ofSeconds(3)).GET().build();
                var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(400,response.statusCode());assertTrue(response.body().contains("CloudMount"));
            }catch(Exception e){throw new RuntimeException(e);}
        }));
        assertTrue(opened.get(),"Browser consent URL must be surfaced from the RC configuration state machine");
        assertEquals("previous-private-connection",Files.readString(paths().credentials()));
    }

}
