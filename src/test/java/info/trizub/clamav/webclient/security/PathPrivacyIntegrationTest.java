package info.trizub.clamav.webclient.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.*;
import info.trizub.clamav.webclient.util.PathPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:path-privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=PathPrivacyBootstrap123456",
    "clamav.config.initial-delay-ms=3600000", "clamav.metrics.clamd-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class PathPrivacyIntegrationTest {
    static final Path ROOT, ALLOWED, TENANT, OTHER, EXISTING, MISSING, ESCAPE, DANGLING, LOOP;
    static {
        try {
            ROOT = Files.createTempDirectory("path-privacy-").toRealPath();
            ALLOWED = Files.createDirectory(ROOT.resolve("allowed")); TENANT = Files.createDirectory(ALLOWED.resolve("tenant"));
            OTHER = Files.createDirectory(ALLOWED.resolve("other"));
            Files.writeString(TENANT.resolve("inside.txt"), "clean"); Files.writeString(OTHER.resolve("existing.txt"), "other tenant");
            EXISTING = Files.writeString(ROOT.resolve("private-existing.txt"), "synthetic private value"); MISSING = ROOT.resolve("private-missing.txt");
            ESCAPE = Files.createSymbolicLink(TENANT.resolve("escape"), EXISTING);
            DANGLING = Files.createSymbolicLink(TENANT.resolve("dangling"), MISSING);
            LOOP = Files.createSymbolicLink(TENANT.resolve("loop"), TENANT.resolve("loop"));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) { values.add("app.allowedScanRoots", ALLOWED::toString); }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.walk(ROOT)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired EndpointService endpoints;
    @Autowired ApiCredentialService credentials;
    @Autowired ScanJobRepository jobs;
    @MockitoBean ScanExecutionService worker;
    String username;
    MockHttpSession session;
    @BeforeEach void loginOperator() throws Exception {
        username = "privacy-" + UUID.randomUUID();
        users.createUser(username, "PathPrivacyOperator123456", Set.of("ROLE_OPERATOR", "ROLE_VIEWER"), true);
        session = (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", username).param("password", "PathPrivacyOperator123456"))
            .andExpect(redirectedUrl("/dashboard")).andReturn().getRequest().getSession(false);
    }
    List<Path> rejectedTargets() { return List.of(EXISTING, MISSING, ESCAPE, DANGLING, LOOP); }
    @Test void operatorPageShowsTheSameGenericErrorWithoutARejectedPathOrCause() throws Exception {
        long before = jobs.count();
        for (Path target : rejectedTargets()) {
            var result = mvc.perform(post("/scan/path").session(session).with(csrf()).param("path", target.toString())
                    .param("endpointId", endpoints.defaultEndpointOrEnsure().getId().toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("errorMsg", PathPolicy.DENIED_MESSAGE)).andReturn();
            assertThat(result.getResponse().getContentAsString()).contains(PathPolicy.DENIED_MESSAGE)
                .doesNotContain(target.toString(), "synthetic private value", "NoSuchFileException");
        }
        assertThat(jobs.count()).isEqualTo(before); verifyNoInteractions(worker);
    }
    @Test void legacySessionApiUsesOneStatusAndMessageForAllRejectedPaths() throws Exception {
        JsonNode expected = null; long before = jobs.count();
        for (Path target : rejectedTargets()) {
            String body = mvc.perform(post("/api/scan/path").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("path", target.toString()))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            JsonNode actual = json.readTree(body); if (expected == null) expected = actual; else assertThat(actual).isEqualTo(expected);
            assertThat(body).doesNotContain(target.toString(), "synthetic private value");
        }
        assertThat(jobs.count()).isEqualTo(before); verifyNoInteractions(worker);
    }
    @Test void legacyBasicApiUsesOneStatusAndMessageForExistingAndMissingOutsideTargets() throws Exception {
        String expected = null;
        for (Path target : List.of(EXISTING, MISSING)) {
            String body = mvc.perform(post("/api/scan/path").secure(true).with(httpBasic(username, "PathPrivacyOperator123456"))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", target.toString()))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            if (expected == null) expected = body; else assertThat(body).isEqualTo(expected);
        }
        verifyNoInteractions(worker);
    }
    @Test void v1SessionDenialsHaveTheSamePublicStatusAndPayloadApartFromTimestamp() throws Exception {
        JsonNode expected = null;
        for (Path target : rejectedTargets()) {
            String body = mvc.perform(post("/api/v1/scans/paths").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("path", target.toString()))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            var actual = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body); actual.remove("timestamp");
            if (expected == null) expected = actual; else assertThat(actual).isEqualTo(expected);
        }
        verifyNoInteractions(worker);
    }
    @Test void restrictedTokenCannotDistinguishMissingGlobalOrOtherTenantTargets() throws Exception {
        String token = credentials.issue("privacy", Set.of("scan:path"), Set.of(), Set.of(TENANT.toString()), Instant.now().plusSeconds(3600)).secret();
        JsonNode expected = null; long before = jobs.count();
        var paths = new ArrayList<>(rejectedTargets()); paths.add(OTHER.resolve("existing.txt")); paths.add(OTHER.resolve("missing.txt"));
        paths.add(TENANT.resolve("missing.txt"));
        for (Path target : paths) {
            String body = mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", target.toString()))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            var actual = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(body); actual.remove("timestamp");
            if (expected == null) expected = actual; else assertThat(actual).isEqualTo(expected);
            assertThat(body).doesNotContain(target.toString());
        }
        assertThat(jobs.count()).isEqualTo(before); verifyNoInteractions(worker);
    }
    @Test void allowedFileAndInRootAliasStillQueueCanonicalJobs() throws Exception {
        Path file = TENANT.resolve("inside.txt").toRealPath(); Path alias = Files.createSymbolicLink(TENANT.resolve("inside-alias"), file);
        for (Path target : List.of(file, alias)) {
            String body = mvc.perform(post("/api/v1/scans/paths").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("path", target.toString()))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            assertThat(jobs.findById(json.readTree(body).get("jobId").asText()).orElseThrow().getTarget()).isEqualTo(file.toString());
        }
        Files.delete(alias);
    }
}
