package com.example.collaborativeworkspace.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.example.collaborativeworkspace.auth.UserRepository;
import com.example.collaborativeworkspace.documents.DocumentService;
import com.example.collaborativeworkspace.support.TestDatabaseGuard;
import com.example.collaborativeworkspace.workspaces.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="WORKSPACE_RUN_DB_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RequestBoundaryIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceMembershipService membership;
    @Autowired DocumentService documents;
    @Autowired PasswordEncoder encoder;
    final UUID owner=UUID.randomUUID(), viewer=UUID.randomUUID(), outsider=UUID.randomUUID();
    final String ownerEmail="boundary-owner-"+owner+"@example.com", viewerEmail="boundary-viewer-"+viewer+"@example.com", outsiderEmail="boundary-outsider-"+outsider+"@example.com";
    UUID workspace, document;
    record Session(HttpClient client, String csrf) {}
    Map<String,Session> sessions=new HashMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",TestDatabaseGuard::verifiedUrl);
        r.add("spring.datasource.username",()->System.getenv("WORKSPACE_TEST_DB_USER"));
        r.add("spring.datasource.password",()->System.getenv("WORKSPACE_TEST_DB_PASSWORD"));
        r.add("workspace.dev.password",()->"test-only-password");
        r.add("workspace.delivery.enabled",()->"false");
        r.add("workspace.observability.projections.enabled",()->"false");
    }
    @BeforeAll void setup() throws Exception {
        users.create(owner,ownerEmail,encoder.encode("test-user-password"));
        users.create(viewer,viewerEmail,encoder.encode("test-user-password"));
        users.create(outsider,outsiderEmail,encoder.encode("test-user-password"));
        workspace=workspaces.create(ownerEmail,"Request boundary fixture").id();
        document=documents.create(workspace,ownerEmail,"Original document").id();
        membership.setRole(workspace,ownerEmail,new ChangeWorkspaceMemberRequest(viewerEmail,WorkspaceRole.VIEWER));
        sessions.put("owner",login(ownerEmail,"test-user-password"));
        sessions.put("viewer",login(viewerEmail,"test-user-password"));
        sessions.put("outsider",login(outsiderEmail,"test-user-password"));
        sessions.put("bootstrap",login("learner","test-only-password"));
        sessions.put("anonymous",new Session(client(),""));
    }
    @AfterAll void cleanup() {
        if(workspace!=null) { jdbc.update("DELETE FROM documents WHERE workspace_id=?",workspace); jdbc.update("DELETE FROM workspaces WHERE id=?",workspace); }
        for(UUID actor:List.of(owner,viewer,outsider)) jdbc.update("DELETE FROM app_users WHERE id=?",actor);
    }
    record Probe(String label,String method,String path,String body,int status) {
        @Override public String toString(){return label;}
    }
    static Stream<Probe> invalidInputs() {
        var cases=new ArrayList<Probe>();
        String create="/api/workspaces/WS/documents";
        String rename=create+"/DOC";
        for(String body:List.of("null","[]","{}","{\"title\":null}","{\"title\":\"\"}","{\"title\":\"  \"}","{\"title\":{}}","{\"title\":[]}","{\"title\":\"\\u0000\"}","{\"title\":\""+"x".repeat(201)+"\"}","{\"title\":","{\"title\":false}","{\"title\":123}","{\"title\":\"valid\",\"unexpected\":true}","{\"title\":\"\\u0000\",\"title\":\"valid\"}","{\"title\":\"valid\"} {}","{\"title\":\"valid\",\"format\":\"UNSUPPORTED\"}")) {
            cases.add(new Probe("invalid-create-"+cases.size(),"POST",create,body,400));
        }
        for(String body:List.of("{}","{\"title\":\"new\"}","{\"title\":\"new\",\"version\":null}","{\"title\":\"new\",\"version\":-1}","{\"title\":\"new\",\"version\":0.5}","{\"title\":\"new\",\"version\":\"0\"}","{\"title\":\"new\",\"version\":9223372036854775808}")) {
            cases.add(new Probe("invalid-rename-"+cases.size(),"PATCH",rename,body,400));
        }
        for(String limit:List.of("0","-1","101","2147483648","1.5","not-a-number")) cases.add(new Probe("document-limit-"+limit,"GET",create+"?limit="+limit,null,400));
        for(String cursor:List.of("","%","a".repeat(201),"not-base64!")) cases.add(new Probe("document-cursor-"+cases.size(),"GET",create+"?cursor="+URLEncoder.encode(cursor,StandardCharsets.UTF_8),null,400));
        for(String q:List.of("","   ","x".repeat(201),"bad\u0000query")) cases.add(new Probe("search-query-"+cases.size(),"GET","/api/workspaces/WS/search?q="+URLEncoder.encode(q,StandardCharsets.UTF_8),null,400));
        for(String limit:List.of("0","-1","51","2147483648","1.5","nan")) cases.add(new Probe("search-limit-"+limit,"GET","/api/workspaces/WS/search?q=hello&limit="+limit,null,400));
        cases.add(new Probe("invalid-uuid","GET","/api/documents/not-a-uuid",null,400));
        cases.add(new Probe("wrong-method","PUT",rename,"{}",405));
        for(String body:List.of("{}","{\"name\":\"valid\"}","{\"name\":\"bad\\nname\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}","{\"name\":\""+"n".repeat(101)+"\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}")) cases.add(new Probe("invalid-revision-"+cases.size(),"POST",rename+"/revisions",body,400));
        for(String body:List.of("{}","{\"revisionId\":\"bad\",\"requestId\":\"bad\",\"expectedEpoch\":0,\"expectedHead\":0}","{\"revisionId\":\"00000000-0000-0000-0000-000000000001\",\"requestId\":\"00000000-0000-0000-0000-000000000002\",\"expectedEpoch\":-1,\"expectedHead\":0}")) cases.add(new Probe("invalid-restore-"+cases.size(),"POST",rename+"/restore",body,400));
        return cases.stream();
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidInputs")
    void rejectsMalformedRequestWithoutMutation(Probe probe) throws Exception {
        long before=countDocuments();
        var response=send(sessions.get("owner"),probe.method,scope(probe.path),probe.body,true,"application/json");
        assertEquals(probe.status,response.statusCode(),probe.label);
        assertEquals(before,countDocuments(),"Rejected request must not create documents");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertFalse(response.body().contains("org.postgresql"));
    }
    @Test void preventsCrossWorkspaceAndViewerWritesAndChecksCsrf() throws Exception {
        String root="/api/workspaces/"+workspace, docs=root+"/documents", doc=docs+"/"+document;
        record Access(String actor,String method,String path,String body,int status) {}
        for(var probe:List.of(
            new Access("anonymous","GET","/api/documents/"+document,null,401),
            new Access("outsider","GET","/api/documents/"+document,null,404),
            new Access("outsider","GET",docs,null,404),
            new Access("outsider","GET",root+"/search?q=hello",null,404),
            new Access("outsider","POST",root+"/search/rebuild",null,404),
            new Access("outsider","GET",doc+"/revisions",null,404),
            new Access("viewer","POST",docs,"{\"title\":\"forbidden\"}",403),
            new Access("viewer","PATCH",doc,"{\"title\":\"forbidden\",\"version\":0}",403),
            new Access("viewer","POST",doc+"/archive","{\"version\":0}",403),
            new Access("viewer","POST",doc+"/checkpoints",null,403),
            new Access("viewer","POST",root+"/search/rebuild",null,403),
            new Access("viewer","POST",root+"/members","{\"email\":\""+outsiderEmail+"\",\"role\":\"OWNER\"}",403),
            new Access("owner","GET","/actuator/prometheus",null,403),
            new Access("anonymous","GET","/actuator/prometheus",null,401),
            new Access("bootstrap","GET","/api/workspaces",null,403))) {
            assertEquals(probe.status,send(sessions.get(probe.actor),probe.method,probe.path,probe.body,true,"application/json").statusCode(),probe.actor+" "+probe.path);
        }
        assertEquals(403,send(sessions.get("owner"),"POST",docs,"{\"title\":\"no csrf\"}",false,"application/json").statusCode());
        var wrong=new Session(sessions.get("owner").client,"invalid-token");
        assertEquals(403,send(wrong,"POST",docs,"{\"title\":\"bad csrf\"}",true,"application/json").statusCode());
    }
    @Test void rejectsNumericEnumAndUnspecifiedBodyTypes() throws Exception {
        String path="/api/workspaces/"+workspace+"/members";
        try {
            var response=send(sessions.get("owner"),"POST",path,"{\"email\":\""+outsiderEmail+"\",\"role\":0}",true,"application/json");
            assertEquals(400,response.statusCode(),"Numeric role must not be interpreted as enum ordinal");
            assertTrue(jdbc.queryForList("SELECT role FROM workspace_members WHERE workspace_id=? AND user_id=?",workspace,outsider).isEmpty());
        } finally {
            // Keep the authorization fixture isolated even when a permissive parser accepts this probe.
            jdbc.update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",workspace,outsider);
        }
    }
    @Test void rejectsUnsupportedContentType() throws Exception {
        assertEquals(415,send(sessions.get("owner"),"POST","/api/workspaces/"+workspace+"/documents","{\"title\":\"wrong type\"}",true,"text/plain").statusCode());
    }
    @Test void validBoundaryTitlesAndStringRolesStillWork() throws Exception {
        String path="/api/workspaces/"+workspace+"/documents";
        for(String title:List.of("123","x".repeat(200),"Unicode \uD83D\uDE80 <literal>")) {
            String body=JsonMapper.builder().build().writeValueAsString(Map.of("title",title));
            var response=send(sessions.get("owner"),"POST",path,body,true,"application/json");
            assertEquals(201,response.statusCode());
            assertEquals(title,JsonMapper.builder().build().readTree(response.body()).path("title").asText());
        }
        var response=send(sessions.get("owner"),"POST","/api/workspaces/"+workspace+"/members",
                "{\"email\":\""+viewerEmail+"\",\"role\":\"VIEWER\"}",true,"application/json");
        assertEquals(200,response.statusCode());
    }
    long countDocuments(){return jdbc.queryForObject("SELECT count(*) FROM documents WHERE workspace_id=?",Long.class,workspace);}
    String scope(String path){return path.replace("WS",workspace.toString()).replace("DOC",document.toString());}
    HttpClient client(){return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    String csrf(HttpClient client) throws Exception { return JsonMapper.builder().build().readTree(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).path("token").asText(); }
    Session login(String name,String password) throws Exception {
        var client=client(); String token=csrf(client);
        var session=new Session(client,token);
        var response=send(session,"POST","/login","username="+URLEncoder.encode(name,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),true,"application/x-www-form-urlencoded");
        assertEquals(302,response.statusCode()); assertFalse(response.headers().firstValue("Location").orElseThrow().contains("error"));
        return new Session(client,csrf(client));
    }
    HttpResponse<String> send(Session session,String method,String path,String body,boolean token,String contentType) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(8));
        if(token) request.header("X-CSRF-TOKEN",session.csrf);
        if(body!=null) request.header("Content-Type",contentType);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return session.client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
