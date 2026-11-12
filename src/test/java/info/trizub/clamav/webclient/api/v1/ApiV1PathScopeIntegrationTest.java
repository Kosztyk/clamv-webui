package info.trizub.clamav.webclient.api.v1;

import com.fasterxml.jackson.databind.*;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import info.trizub.clamav.webclient.security.ApiV1Access;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import xyz.capybara.clamav.Platform;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:token-path-scopes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "clamav.bootstrap.admin.password=PathScopeLocalAdmin456789",
        "clamav.metrics.clamd-initial-delay-ms=3600000", "clamav.config.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiV1PathScopeIntegrationTest {
    static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("api-path-scopes-").toRealPath(); } catch (Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("app.allowedScanRoots", ROOT::toString);
    }
    @AfterAll static void removeFiles() throws Exception {
        try (var files = Files.walk(ROOT)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApiCredentialService credentials;
    @Autowired ApiCredentialRepository tokens;
    @Autowired ScanJobRepository jobs;
    @Autowired AuditEventRepository audits;
    @Autowired EndpointService endpoints;
    @Autowired ClamdEndpointRepository endpointRows;
    @Autowired javax.sql.DataSource database;
    @MockitoBean ScanExecutionService execution;
    @MockitoBean DaemonScanPolicy daemon;
    @MockitoSpyBean ApiV1Access access;
    @MockitoSpyBean SettingsService settings;
    private Path folder, a, b;
    private ClamdEndpoint endpoint;
    private long sequence;

    @BeforeEach void prepare() throws Exception {
        audits.deleteAll(); jobs.deleteAll(); tokens.deleteAll(); endpointRows.deleteAll();
        endpoints.ensureDefaultEndpoint(); endpoint = endpoints.defaultEndpointOrEnsure();
        folder = Files.createDirectory(ROOT.resolve(UUID.randomUUID().toString()));
        a = Files.createDirectory(folder.resolve("a")); b = Files.createDirectory(folder.resolve("b"));
        sequence = 0;
    }
    private ApiCredentialService.Created token(Path... roots) {
        return credentials.issue("path scope test", Set.of("jobs:read", "audit:read", "scan:path", "scan:upload"),
                Set.of(endpoint.getId()), new HashSet<>(Arrays.stream(roots).map(Path::toString).toList()), Instant.now().plusSeconds(3600));
    }
    private MockHttpServletRequestBuilder read(String path, String secret) {
        return get("/api/v1" + path).secure(true).header("Authorization", "Bearer " + secret);
    }
    private JsonNode list(String secret, int page, int size) throws Exception {
        return json.readTree(mvc.perform(read("/jobs", secret).param("page", Integer.toString(page)).param("size", Integer.toString(size)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>(); for (JsonNode item : page.get("items")) ids.add(item.get("id").asText()); return ids;
    }
    private ScanJob job(String id, ScanJobType type, String target) {
        var row = new ScanJob(); row.setId(id); row.setType(type); row.setTarget(target); row.setEndpoint(endpoint);
        row.setStatus(ScanJobStatus.FINISHED); row.setVerdict(ScanVerdict.VIRUS_FOUND);
        row.setSubmittedBy("different user"); row.setSubmittedTransport("BROWSER");
        row.setSubmittedAt(Instant.parse("2026-11-10T12:00:00Z").plusSeconds(sequence++));
        row.setFinishedAt(row.getSubmittedAt().plusSeconds(1)); row.setFoundVirusesJson("{\"private\":[\"" + id + "\"]}");
        return row;
    }
    private ScanJob upload(String id, ApiCredentialService.Created owner) {
        var row = job(id, ScanJobType.UPLOAD, id + ".txt");
        row.setSubmittedBy("token:" + owner.credential().getId()); row.setSubmittedTransport("API_TOKEN"); return row;
    }
    private void audit(String jobId, Long endpointId) {
        var row = new AuditEvent("test actor", "SCAN_RESULT", "private detail", "VIRUS_FOUND", jobId, "127.0.0.1", "test");
        row.setEndpointId(endpointId); row.setTransport("API_TOKEN"); audits.save(row);
    }

    @Test void listsOnlyInRootPathWatchAndOwnUploadsWithAccurateTotals() throws Exception {
        var owner = token(a); var other = token(b);
        var otherEndpoint = endpoints.create("secondary", "localhost", 3311, Platform.UNIX, true);
        var wrongEndpoint = job("wrong-endpoint", ScanJobType.PATH, a.resolve("file").toString()); wrongEndpoint.setEndpoint(otherEndpoint);
        var wrongActorCase = upload("wrong-actor-case", owner); wrongActorCase.setSubmittedBy(wrongActorCase.getSubmittedBy().toUpperCase(Locale.ROOT));
        var wrongTransportCase = upload("wrong-transport-case", owner); wrongTransportCase.setSubmittedTransport("api_token");
        jobs.saveAll(List.of(job("visible-path", ScanJobType.PATH, a.resolve("file").toString()),
                job("visible-watch", ScanJobType.WATCH, a.resolve("watched").toString()), upload("visible-upload", owner),
                job("outside", ScanJobType.PATH, b.resolve("file").toString()), upload("foreign-upload", other),
                job("case-overmatch", ScanJobType.PATH, folder.resolve("A/file").toString()),
                job("sibling-overmatch", ScanJobType.WATCH, folder.resolve("a-sibling/file").toString()),
                job("dot-overmatch", ScanJobType.PATH, a + "/../b/file"), wrongEndpoint, wrongActorCase, wrongTransportCase));
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(database).queryForObject(
                "select count(*) from scan_jobs where target=?", Long.class, a.resolve("file").toString())).isEqualTo(3L);
        JsonNode result = list(owner.secret(), 0, 25);
        assertThat(ids(result)).containsExactlyInAnyOrder("visible-path", "visible-watch", "visible-upload");
        assertThat(result.get("totalItems").asLong()).isEqualTo(3); assertThat(result.get("totalPages").asInt()).isEqualTo(1);
        mvc.perform(read("/jobs", owner.secret()).param("status", "QUEUED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }

    @ParameterizedTest @ValueSource(strings = {"", "/progress"})
    void detailsAndProgressApplyTheSameRootEndpointAndUploadRules(String suffix) throws Exception {
        var owner = token(a); var other = token(b);
        jobs.saveAll(List.of(job("inside", ScanJobType.PATH, a.resolve("gone-after-scan").toString()),
                job("watch-inside", ScanJobType.WATCH, a.toString()), job("outside", ScanJobType.PATH, b.resolve("file").toString()),
                job("legacy-dot", ScanJobType.PATH, a + "/../b/file"), upload("own-upload", owner), upload("foreign-upload", other)));
        for (String id : List.of("inside", "watch-inside", "own-upload"))
            mvc.perform(read("/jobs/" + id + suffix, owner.secret())).andExpect(status().isOk());
        for (String id : List.of("outside", "legacy-dot", "foreign-upload")) {
            String body = mvc.perform(read("/jobs/" + id + suffix, owner.secret())).andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("foreign-upload.txt", "private detail", b.toString());
        }
    }

    @Test void symlinkRootIsStoredCanonicallyAndWorksForSubmissionAndHistoryAfterFileDeletion() throws Exception {
        Path alias = Files.createSymbolicLink(folder.resolve("root-alias"), a);
        Path file = Files.writeString(a.resolve("file.txt"), "clean fixture");
        var owner = token(alias);
        assertThat(owner.credential().getPathRoots()).isEqualTo(a.toRealPath().toString());
        String body = mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + owner.secret())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", alias.resolve("file.txt").toString()))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("jobId").asText();
        assertThat(jobs.findById(id).orElseThrow().getTarget()).isEqualTo(file.toRealPath().toString());
        Files.delete(file);
        mvc.perform(read("/jobs/" + id, owner.secret())).andExpect(status().isOk());
        assertThat(ids(list(owner.secret(), 0, 25))).containsExactly(id);
        verify(execution).enqueue(id);
    }

    @Test void retargetingTheOriginalRootAliasDoesNotRetargetTheStoredGrant() throws Exception {
        Path alias = Files.createSymbolicLink(folder.resolve("root-alias"), a);
        Files.writeString(a.resolve("file.txt"), "a"); Files.writeString(b.resolve("file.txt"), "b");
        var owner = token(alias);
        Files.delete(alias); Files.createSymbolicLink(alias, b);
        mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + owner.secret())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", alias.resolve("file.txt").toString()))))
                .andExpect(status().isForbidden());
        assertThat(jobs.count()).isZero();
    }

    @Test void aliasChangedAfterAuthorizationCannotChangeThePathPassedToJobCreation() throws Exception {
        Path alias = Files.createSymbolicLink(folder.resolve("request-alias"), a);
        Path expected = Files.writeString(a.resolve("file.txt"), "a").toRealPath(); Files.writeString(b.resolve("file.txt"), "b");
        var owner = token(a);
        doAnswer(invocation -> {
            Path authorized = (Path) invocation.callRealMethod();
            Files.delete(alias); Files.createSymbolicLink(alias, b); return authorized;
        }).when(access).path(any(), eq(alias.resolve("file.txt").toString()));
        String body = mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + owner.secret())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", alias.resolve("file.txt").toString()))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(jobs.findById(json.readTree(body).get("jobId").asText()).orElseThrow().getTarget()).isEqualTo(expected.toString());
    }

    @Test void wildcardCharactersInRootsAreLiteralSqlCharacters() throws Exception {
        Path special = Files.createDirectory(folder.resolve("team_%!"));
        var owner = token(special);
        jobs.saveAll(List.of(job("literal", ScanJobType.PATH, special.resolve("file").toString()),
                job("wildcard-overmatch", ScanJobType.PATH, folder.resolve("team_AX!/file").toString())));
        assertThat(ids(list(owner.secret(), 0, 25))).containsExactly("literal");
    }

    @Test void canonicalRootsAlsoWorkWhenTheGlobalAllowedRootIsAnAlias() throws Exception {
        Path globalAlias = Files.createSymbolicLink(folder.resolve("global-alias"), a);
        Path tokenAlias = Files.createSymbolicLink(folder.resolve("token-alias"), a);
        doReturn(List.of(globalAlias)).when(settings).allowedRoots();
        var owner = token(tokenAlias);
        assertThat(owner.credential().getPathRoots()).isEqualTo(a.toRealPath().toString());
        jobs.save(job("inside", ScanJobType.PATH, a.resolve("file").toString()));
        assertThat(ids(list(owner.secret(), 0, 25))).containsExactly("inside");
    }

    @Test void metadataBatchesFillPagesAndCountsBeyondOneThousandRowsWithStableTies() throws Exception {
        var owner = token(a); var rows = new ArrayList<ScanJob>();
        for (int i = 0; i < 1201; i++) rows.add(job(String.format(Locale.ROOT, "allowed-%04d", i), ScanJobType.PATH, a.resolve("file-" + i).toString()));
        for (int i = 0; i < 70; i++) {
            rows.add(job("dot-" + i, ScanJobType.PATH, a + "/../b/file-" + i));
            rows.add(job("case-" + i, ScanJobType.PATH, folder.resolve("A/file-" + i).toString()));
        }
        rows.forEach(row -> row.setSubmittedAt(Instant.parse("2026-11-10T12:00:00Z"))); jobs.saveAll(rows);
        JsonNode first = list(owner.secret(), 0, 7), second = list(owner.secret(), 1, 7), last = list(owner.secret(), 171, 7);
        assertThat(first.get("totalItems").asLong()).isEqualTo(1201); assertThat(first.get("totalPages").asInt()).isEqualTo(172);
        assertThat(ids(first)).containsExactly("allowed-1200", "allowed-1199", "allowed-1198", "allowed-1197", "allowed-1196", "allowed-1195", "allowed-1194");
        assertThat(ids(second)).hasSize(7).doesNotContainAnyElementsOf(ids(first));
        assertThat(ids(last)).containsExactly("allowed-0003", "allowed-0002", "allowed-0001", "allowed-0000");
    }

    @Test void auditDoesNotProvideAnAlternateReadOfHiddenJobsOrUnlinkedAdministrativeEvents() throws Exception {
        var owner = token(a); var other = token(b);
        jobs.saveAll(List.of(job("inside", ScanJobType.PATH, a.resolve("file").toString()),
                job("outside", ScanJobType.WATCH, b.resolve("file").toString()), upload("own", owner), upload("foreign", other)));
        for (String id : List.of("inside", "outside", "own", "foreign", "INSIDE", "missing")) audit(id, endpoint.getId());
        audit(null, endpoint.getId()); audit("inside", Long.MAX_VALUE);
        var result = json.readTree(mvc.perform(read("/audit", owner.secret())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(result.get("totalItems").asLong()).isEqualTo(2);
        var visible = new ArrayList<String>(); for (JsonNode item : result.get("items")) visible.add(item.get("jobId").asText());
        assertThat(visible).containsExactlyInAnyOrder("inside", "own");
        assertThat(result.toString()).doesNotContain("private detail", "outside", "foreign");
    }

    @Test void auditPaginationCrossesMetadataBatchesWithoutLeakingOvermatchCounts() throws Exception {
        var owner = token(a);
        jobs.saveAll(List.of(job("inside", ScanJobType.PATH, a.resolve("file").toString()), job("outside", ScanJobType.PATH, b.resolve("file").toString())));
        for (int i = 0; i < 501; i++) audit("inside", endpoint.getId());
        for (int i = 0; i < 35; i++) { audit("INSIDE", endpoint.getId()); audit("outside", endpoint.getId()); }
        mvc.perform(read("/audit", owner.secret()).param("page", "5").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(501)).andExpect(jsonPath("$.totalPages").value(6))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].jobId").value("inside"));
    }

    @Test void emptyRootsRetainEndpointWideReadsButStillDenyPathSubmission() throws Exception {
        var owner = token();
        jobs.saveAll(List.of(job("outside", ScanJobType.PATH, b.resolve("file").toString()), job("upload", ScanJobType.UPLOAD, "another-user.txt")));
        assertThat(ids(list(owner.secret(), 0, 25))).containsExactlyInAnyOrder("outside", "upload");
        mvc.perform(read("/jobs/outside", owner.secret())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/scans/paths").secure(true).header("Authorization", "Bearer " + owner.secret())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", b.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/jobs").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test void legacyUnresolvedAliasRootsAndUnavailableRootsFailClosedUntilReissued() throws Exception {
        Path alias = Files.createSymbolicLink(folder.resolve("legacy-root"), a);
        var legacy = token(a); legacy.credential().setPathRoots(alias.toString()); tokens.save(legacy.credential());
        jobs.save(job("inside", ScanJobType.PATH, a.resolve("file").toString()));
        assertThat(ids(list(legacy.secret(), 0, 25))).isEmpty();
        mvc.perform(read("/jobs/inside", legacy.secret())).andExpect(status().isForbidden());
        var current = token(a); Files.delete(a);
        assertThat(ids(list(current.secret(), 0, 25))).isEmpty();
        mvc.perform(read("/jobs/inside", current.secret())).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "file", "relative", "escape", "resolved-comma", "too-long"})
    void issuanceRejectsRootsThatCannotBeStoredAsSafeCanonicalDirectories(String kind) throws Exception {
        if (kind.equals("too-long")) {
            var many = new ArrayList<Path>();
            for (int i = 0; i < 32; i++) many.add(Files.createDirectory(folder.resolve("root-" + i + "-" + "x".repeat(40))));
            assertThatThrownBy(() -> token(many.toArray(Path[]::new))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("storage limit");
            assertThat(tokens.count()).isZero(); return;
        }
        Path root = switch (kind) {
            case "file" -> Files.writeString(folder.resolve("file.txt"), "x");
            case "relative" -> Path.of("relative-root");
            case "escape" -> Files.createSymbolicLink(folder.resolve("escape"), ROOT.getParent());
            case "resolved-comma" -> Files.createSymbolicLink(folder.resolve("clean-alias"), Files.createDirectory(folder.resolve("comma,root")));
            default -> folder.resolve("missing");
        };
        assertThatThrownBy(() -> token(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tokens.count()).isZero();
    }

    @Test void nullRootMembersProduceAValidationErrorBeforeIssuance() {
        var roots = new HashSet<String>(); roots.add(null);
        assertThatThrownBy(() -> credentials.issue("invalid", Set.of("jobs:read"), Set.of(), roots, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokens.count()).isZero();
    }
}
