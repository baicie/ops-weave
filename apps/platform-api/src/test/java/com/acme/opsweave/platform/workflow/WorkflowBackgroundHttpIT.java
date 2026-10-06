package com.acme.opsweave.platform.workflow;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.platform.PlatformApplication;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.identity.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

/** Actual HTTP login and PostgreSQL output; isolated tenant and explicit loopback protocol/source Fixtures. */
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowBackgroundHttpIT {
    static final String TENANT="authority-http-"+UUID.randomUUID(), ORIGIN="http://127.0.0.1:5173";
    static final String SUBJECT="background-fixture-subject", OWNER="background-fixture-author", SOURCE="background-http-fixture";
    static final String SECRET="protocol-fixture-secret-not-production";
    static final RSAKey KEY;
    static final HttpServer IDP;
    static final ExecutorService EXECUTOR=Executors.newFixedThreadPool(2);
    static final Map<String,Map<String,String>> CODES=new ConcurrentHashMap<>();
    static final Path GRANTS,POLICY;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("fixture-key").generate();
            GRANTS=Files.createTempFile("opsweave-authority-identity-",".json");
            POLICY=Files.createTempFile("opsweave-authority-background-",".json");
            IDP=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);IDP.setExecutor(EXECUTOR);
            IDP.createContext("/jwks",e->send(e,200,new JWKSet(KEY.toPublicJWK()).toJSONObject()));
            IDP.createContext("/token",WorkflowBackgroundHttpIT::exchange);IDP.start();
            identity(1);optIn(true);
        } catch(Exception failure) {throw new ExceptionInInitializerError(failure);}
    }
    static String issuer(){return "http://127.0.0.1:"+IDP.getAddress().getPort();}
    static void identity(int revision)throws Exception {
        var grant=Map.of("issuer",issuer(),"externalSubject",SUBJECT,"subjectId",OWNER,"tenantId",TENANT,"revision",revision,"enabled",true,
            "permissions",List.of("entity.read","entity.manage","source.sync"),"scope",Map.of("tenantWide",true,"resources",List.of()));
        Files.writeString(GRANTS,CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","grants",List.of(grant))));
    }
    static void optIn(boolean enabled)throws Exception {Files.writeString(POLICY,CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","subjects",enabled?List.of(SUBJECT):List.of())));}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.address",()->"127.0.0.1");p.add("opsweave.auth.mode",()->"oidc");p.add("opsweave.auth.oidc.loopback-test",()->"true");
        p.add("opsweave.inventory.store",()->"postgres");p.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));
        p.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));p.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        p.add("opsweave.zabbix.mode",()->"fixture");p.add("opsweave.zabbix.source-instance-id",()->SOURCE);
        p.add("opsweave.auth.oidc.issuer",WorkflowBackgroundHttpIT::issuer);p.add("opsweave.auth.oidc.authorization-uri",()->issuer()+"/authorize");
        p.add("opsweave.auth.oidc.token-uri",()->issuer()+"/token");p.add("opsweave.auth.oidc.jwk-set-uri",()->issuer()+"/jwks");
        p.add("opsweave.auth.oidc.client-id",()->"opsweave");p.add("opsweave.auth.oidc.client-secret",()->SECRET);
        p.add("opsweave.auth.oidc.public-origin",()->ORIGIN);p.add("opsweave.auth.oidc.grants-file",GRANTS::toString);
        p.add("opsweave.workflow.runtime-grants-file",POLICY::toString);
    }
    @LocalServerPort int port;
    @Autowired InventoryWiring wiring;
    @Autowired IngestZabbixHostsUseCase hosts;
    @Autowired WorkflowController workflows;
    @Autowired WorkflowBackgroundAuthorities background;
    @Autowired FileIdentityGrants grants;
    @Autowired OidcSettings settings;
    final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    String csrf;
    @AfterEach void closeClient(){http.close();}
    @AfterAll static void close()throws Exception {IDP.stop(0);EXECUTOR.shutdownNow();Files.deleteIfExists(GRANTS);Files.deleteIfExists(POLICY);}
    HttpResponse<String> request(String path,Object body,boolean origin)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(body==null)b.GET();else {b.header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf);if(origin)b.header("Origin",ORIGIN);b.POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));}
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(String suffix,Object body)throws Exception {
        var r=request("/api/v1/integrations/workflows"+suffix,body,true);assertEquals(200,r.statusCode(),suffix+": "+r.body());return CatalogJson.JSON.readTree(r.body());
    }
    void login()throws Exception {
        var begin=request("/api/v1/auth/login/opsweave",null,false);assertEquals(302,begin.statusCode());
        var q=params(URI.create(begin.headers().firstValue("Location").orElseThrow()).getRawQuery());
        String code=UUID.randomUUID().toString();CODES.put(code,q);
        assertEquals(302,request("/api/v1/auth/callback?code="+code+"&state="+URLEncoder.encode(q.get("state"),StandardCharsets.UTF_8),null,false).statusCode());
        var session=CatalogJson.JSON.readTree(request("/api/v1/auth/session",null,false).body());assertTrue(session.get("authenticated").asBoolean());
        assertEquals(TENANT,session.get("principal").get("tenantId").asString());csrf=session.get("csrfToken").asString();
    }
    Principal principal(){return new Principal(new SubjectId(OWNER),new TenantId(TENANT),Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC),ResourceScope.tenantWide());}
    WorkflowDefinition graph(){
        var model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals("builtin.host")).findFirst().orElseThrow();
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition("authority-"+UUID.randomUUID(),1,"LOCALTEST delegated Host",new Source("ZABBIX_HOST",SOURCE),new Target(model.id(),1,model.digest()),
            List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("map",Type.MAP,"1",Map.of("name","hostname","ip","ip")),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),
            List.of(new Edge("source","map"),new Edge("map","validate"),new Edge("validate","output"))));
    }
    void publish(WorkflowDefinition d,UUID batch)throws Exception {
        var layout=new LinkedHashMap<String,Position>();for(int i=0;i<d.nodes().size();i++)layout.put(d.nodes().get(i).id(),new Position(100,i*100));
        success("/drafts",Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",0));
        var receipt=success("/preview",Map.of("id",d.id(),"revision",1,"digest",d.digest(),"editVersion",1,"dryRun",true,"syncRunId",batch));
        success("/publish",Map.of("id",d.id(),"revision",1,"digest",d.digest(),"editVersion",1,"previewId",receipt.get("receipt").get("id").asString()));
    }
    UUID batch(){var result=hosts.execute(principal(),SOURCE);assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,result.kind());return result.syncRunId();}
    WorkflowRuntime.Task stored(String id){return wiring.workflows().transaction(new TenantId(TENANT),s->s.task(OWNER,id).orElseThrow());}
    WorkflowRuntime.Execution execution(UUID batch){return workflows.runtime().executions(principal()).stream().filter(e->batch.equals(e.syncRunId())).findFirst().orElseThrow();}
    @Test void trustedLoginBoundedDelegationPgOutputRestartStopAndRevocation()throws Exception {
        login();var d=graph();publish(d,batch());assertTrue(success("/runtime",null).get("backgroundAvailable").asBoolean());
        var command=new LinkedHashMap<String,Object>(Map.of("id",d.id(),"revision",1,"digest",d.digest(),"settings",Map.of("identityField","entity_id","nameField","hostname"),"expectedGeneration",0));
        command.put("requestId",UUID.randomUUID());var forged=new LinkedHashMap<>(command);forged.put("authority",Map.of("tenantId","attacker","permissions",List.of("*")));
        assertEquals(400,request("/api/v1/integrations/workflows/runtime/start",forged,true).statusCode());
        assertEquals(403,request("/api/v1/integrations/workflows/runtime/start",command,false).statusCode());
        assertTrue(workflows.runtime().tasks(principal()).isEmpty());
        var originalStart=success("/runtime/start",command);var originalCommand=new LinkedHashMap<>(command);
        assertEquals(originalStart,success("/runtime/start",command));assertEquals(originalStart,success("/runtime/commands/"+command.get("requestId"),null));
        assertEquals(400,request("/api/v1/integrations/workflows/runtime/commands/"+command.get("requestId")+"?tenantId=forged",null,false).statusCode());
        var running=originalStart.get("task");var auth=running.get("authorization");assertEquals(5,auth.size());assertEquals(20,auth.get("maxBatches").asInt());
        for(String field:List.of("authority","grantDigest","externalSubject","issuer"))assertFalse(running.has(field));
        for(String secret:List.of(SECRET,issuer(),SUBJECT))assertFalse(running.toString().contains(secret));
        var first=batch();background.poll(workflows.runtime());var executed=execution(first);assertEquals("SUCCEEDED",executed.state());assertEquals("fixture",executed.origin());
        assertEquals(UUID.fromString(auth.get("id").asString()),executed.authorizationId());assertEquals(2,executed.entityIds().size());
        for(String id:executed.entityIds()){var entity=new EntityId(UUID.fromString(id));assertTrue(wiring.query().find(new TenantId(TENANT),entity).isPresent());assertTrue(wiring.query().find(new TenantId(TENANT+"-other"),entity).isEmpty());}
        assertEquals(1,stored(d.id()).authority().consumedBatches());
        var logout=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/logout")).header("Origin",ORIGIN).header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(200,http.send(logout,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(401,request("/api/v1/integrations/workflows/runtime",null,false).statusCode());
        var restarted=new WorkflowBackgroundAuthorities(new OidcSessions(grants,settings),grants,POLICY.toString(),Clock.systemUTC());
        var second=batch();restarted.poll(workflows.runtime());assertEquals("SUCCEEDED",execution(second).state());assertEquals(executed.authorizationId(),execution(second).authorizationId());
        assertEquals(2,stored(d.id()).authority().consumedBatches());
        login();command.put("expectedGeneration",1);command.put("requestId",UUID.randomUUID());assertEquals("STOPPED",success("/runtime/stop",command).get("task").get("state").asString());
        var stoppedCursor=stored(d.id()).cursor();var afterStop=batch();restarted.poll(workflows.runtime());assertTrue(workflows.runtime().executions(principal()).stream().noneMatch(e->afterStop.equals(e.syncRunId())));assertEquals(stoppedCursor,stored(d.id()).cursor());
        command.put("expectedGeneration",2);command.put("requestId",UUID.randomUUID());success("/runtime/start",command);var originalCursor=stored(d.id()).cursor();optIn(false);background.poll(workflows.runtime());
        assertEquals("FAILED",stored(d.id()).state());assertEquals("AUTHORIZATION_REVOKED",stored(d.id()).error());assertEquals(originalCursor,stored(d.id()).cursor());assertFalse(success("/runtime",null).get("backgroundAvailable").asBoolean());
        assertEquals(originalStart,success("/runtime/start",originalCommand));assertEquals("FAILED",stored(d.id()).state());assertEquals(3,stored(d.id()).generation());
        optIn(true);command.put("expectedGeneration",3);command.put("requestId",UUID.randomUUID());success("/runtime/start",command);identity(2);restarted.poll(workflows.runtime());
        assertEquals("AUTHORIZATION_REVOKED",stored(d.id()).error());assertEquals(0,stored(d.id()).authority().consumedBatches());assertEquals(401,request("/api/v1/integrations/workflows/runtime",null,false).statusCode());
    }
    static Map<String,String> params(String query){var result=new HashMap<String,String>();for(String pair:query.split("&")){var p=pair.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return result;}
    static void exchange(HttpExchange e)throws java.io.IOException {
        try {
            assertEquals("Basic "+Base64.getEncoder().encodeToString(("opsweave:"+SECRET).getBytes(StandardCharsets.UTF_8)),e.getRequestHeaders().getFirst("Authorization"));
            var form=params(new String(e.getRequestBody().readNBytes(8192),StandardCharsets.UTF_8));var auth=CODES.remove(form.get("code"));assertNotNull(auth);
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));assertEquals(auth.get("code_challenge"),challenge);
            var now=Instant.now();var claims=new JWTClaimsSet.Builder().issuer(issuer()).audience("opsweave").subject(SUBJECT).issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("nonce",auth.get("nonce")).claim("tenantId","attacker").claim("permissions",List.of("*")).build();
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture-key").build(),claims);jwt.sign(new RSASSASigner(KEY));
            send(e,200,Map.of("token_type","Bearer","access_token","fixture-token-must-stay-in-java","expires_in",600,"id_token",jwt.serialize()));
        } catch(Throwable invalid) {send(e,400,Map.of("error","invalid_grant"));}
    }
    static void send(HttpExchange e,int status,Object value)throws java.io.IOException {byte[] body=CatalogJson.JSON.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);e.close();}
}
