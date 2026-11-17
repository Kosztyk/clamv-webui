package info.trizub.clamav.webclient.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.EndpointService;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * OpenAPI v1 response-shape validation against actual Spring MVC handlers. Do not replace with
 * source-only route matching: this exercises serialized HTTP output.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:issue55-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=update",
      "spring.h2.console.enabled=false",
      "spring.task.scheduling.enabled=false",
      "clamav.bootstrap.admin.password=Issue55ContractTestOnlyPass123456"
    })
@AutoConfigureMockMvc
class Issue55OpenApiContractIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapping;

  @Autowired private EndpointService endpoints;
  @Autowired private ScanJobRepository jobRepo;

  private JsonNode spec() throws Exception {
    try (InputStream input = new ClassPathResource("openapi/clamav-v1.yaml").getInputStream()) {
      Object root = new Yaml().load(input);
      return mapper.valueToTree(root);
    }
  }

  private JsonNode responseSchema(String path, String method, int status) throws Exception {
    JsonNode document = spec();
    JsonNode response =
        document
            .path("paths")
            .path(path)
            .path(method.toLowerCase())
            .path("responses")
            .path(Integer.toString(status));
    if (response.has("$ref")) {
      String reference = response.get("$ref").asText();
      assertThat(reference).startsWith("#/");
      response = document.at(reference.substring(1));
    }
    JsonNode schema = response.path("content").path("application/json").path("schema");
    assertThat(schema.isMissingNode())
        .as("Undocumented JSON response: %s %s -> %s", method, path, status)
        .isFalse();
    return schema;
  }

  private void matches(String path, String method, int status, MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).as("HTTP %s %s", method, path).isEqualTo(status);
    JsonNode payload = mapper.readTree(result.getResponse().getContentAsByteArray());
    assertThat(payload).as("JSON response for %s %s", method, path).isNotNull();
    validate(responseSchema(path, method, status), payload, method + " " + path);
  }

  private void validate(JsonNode schema, JsonNode value, String location) throws Exception {
    if (schema.has("$ref")) {
      String pointer = schema.get("$ref").asText();
      assertThat(pointer).startsWith("#/");
      JsonNode definition = spec().at(pointer.substring(1));
      assertThat(definition.isMissingNode()).as("Unresolved reference %s", pointer).isFalse();
      validate(definition, value, location);
      return;
    }
    if (value.isNull()) {
      assertThat(schema.path("nullable").asBoolean()).as(location + " must not be null").isTrue();
      return;
    }
    String type = schema.path("type").asText("");
    switch (type) {
      case "object":
        {
          assertThat(value.isObject()).as(location + " must be object").isTrue();
          for (JsonNode key : schema.path("required")) {
            assertThat(value.has(key.asText()))
                .as(location + " missing required " + key.asText())
                .isTrue();
          }
          JsonNode props = schema.path("properties");
          Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode fieldSchema = props.path(field.getKey());
            if (!fieldSchema.isMissingNode()) {
              validate(fieldSchema, field.getValue(), location + "." + field.getKey());
            } else if (schema.has("additionalProperties")) {
              JsonNode extra = schema.path("additionalProperties");
              if (extra.isBoolean()) {
                assertThat(extra.asBoolean()).as("Undocumented field " + field.getKey()).isTrue();
              } else {
                validate(extra, field.getValue(), location + "." + field.getKey());
              }
            }
          }
          break;
        }
      case "array":
        {
          assertThat(value.isArray()).as(location + " must be array").isTrue();
          if (schema.has("maxItems"))
            assertThat(value.size()).isLessThanOrEqualTo(schema.get("maxItems").asInt());
          int index = 0;
          for (JsonNode item : value)
            validate(schema.path("items"), item, location + "[" + index++ + "]");
          break;
        }
      case "integer":
        assertThat(value.isIntegralNumber()).as(location + " must be integer").isTrue();
        break;
      case "number":
        assertThat(value.isNumber()).as(location + " must be number").isTrue();
        break;
      case "boolean":
        assertThat(value.isBoolean()).as(location + " must be boolean").isTrue();
        break;
      case "string":
        {
          assertThat(value.isTextual()).as(location + " must be string").isTrue();
          if ("date-time".equals(schema.path("format").asText())) Instant.parse(value.asText());
          if (schema.has("maxLength"))
            assertThat(value.asText().length())
                .isLessThanOrEqualTo(schema.get("maxLength").asInt());
          break;
        }
      default:
        break;
    }
    if (schema.has("enum")) {
      boolean found = false;
      for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
      assertThat(found).as(location + " outside documented enum").isTrue();
    }
  }

  private void fieldsAgree(String schemaName, Class<?> record) throws Exception {
    assertThat(record.isRecord()).isTrue();
    JsonNode props = spec().path("components").path("schemas").path(schemaName).path("properties");
    assertThat(props.isObject()).as("Missing schema %s", schemaName).isTrue();
    Set<String> javaFields = new HashSet<>();
    for (RecordComponent field : record.getRecordComponents()) javaFields.add(field.getName());
    Set<String> documented = new HashSet<>();
    props.fieldNames().forEachRemaining(documented::add);
    assertThat(documented)
        .as("Java DTO/OpenAPI drift for " + schemaName)
        .containsExactlyInAnyOrderElementsOf(javaFields);
  }

  @Test
  void springHandlerMethodsAreExactlyTheDocumentedOperations() throws Exception {
    Set<String> implemented = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entry :
        mapping.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      for (String full : info.getPatternValues()) {
        if (!full.startsWith("/api/v1/")) continue;
        String path = full.substring("/api/v1".length());
        info.getMethodsCondition()
            .getMethods()
            .forEach(verb -> implemented.add(verb.name().toLowerCase() + " " + path));
      }
    }
    Set<String> documented = new HashSet<>();
    JsonNode paths = spec().path("paths");
    paths
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fieldNames()
                    .forEachRemaining(
                        verb -> documented.add(verb.toLowerCase() + " " + path.getKey())));
    assertThat(implemented)
        .as("Undocumented or removed Spring MVC API v1 endpoints")
        .containsExactlyInAnyOrderElementsOf(documented);
  }

  @Test
  void javaDtoRecordFieldsCannotSilentlyDriftFromTheOpenApiSpec() throws Exception {
    fieldsAgree("Endpoint", ApiV1Dtos.Endpoint.class);
    fieldsAgree("Job", ApiV1Dtos.Job.class);
    fieldsAgree("JobError", ApiV1Dtos.JobError.class);
    fieldsAgree("Audit", ApiV1Dtos.Audit.class);
    fieldsAgree("UploadSubmission", ApiV1Dtos.Submitted.class);
    fieldsAgree("ClamdHealth", ApiV1Controller.ClamdHealth.class);
    fieldsAgree("Token", ApiTokenAdminController.TokenView.class);
    fieldsAgree("IssuedToken", ApiTokenAdminController.Created.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void actualSuccessAndErrorHttpPayloadsMatchThePublishedSchema() throws Exception {
    endpoints.ensureDefaultEndpoint();
    matches(
        "/auth",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/auth").secure(true)).andReturn());
    matches(
        "/csrf",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/csrf").secure(true)).andReturn());
    matches(
        "/health/live",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/health/live").secure(true)).andReturn());
    matches(
        "/health/ready",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/health/ready").secure(true)).andReturn());
    matches(
        "/endpoints",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/endpoints").secure(true)).andReturn());
    matches(
        "/jobs",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/jobs").secure(true)).andReturn());
    matches(
        "/audit",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/audit").secure(true)).andReturn());
    matches(
        "/tokens",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/tokens").secure(true)).andReturn());
    matches(
        "/jobs",
        "get",
        400,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/jobs").secure(true).param("size", "100000"))
            .andReturn());
    matches(
        "/jobs/{id}",
        "get",
        404,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/jobs/no-such-job-issue55").secure(true))
            .andReturn());
    matches(
        "/scans/paths",
        "post",
        400,
        mvc.perform(
                MockMvcRequestBuilders.post("/api/v1/scans/paths")
                    .secure(true)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"path\":\"\"}"))
            .andReturn());
    matches(
        "/scans/paths",
        "post",
        400,
        mvc.perform(
                MockMvcRequestBuilders.post("/api/v1/scans/paths")
                    .secure(true)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"path\":"))
            .andReturn());
    matches(
        "/scans/uploads",
        "post",
        400,
        mvc.perform(
                MockMvcRequestBuilders.multipart("/api/v1/scans/uploads").secure(true).with(csrf()))
            .andReturn());
    MvcResult token =
        mvc.perform(
                MockMvcRequestBuilders.post("/api/v1/tokens")
                    .secure(true)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"label\":\"contract-fixture-issue55\",\"scopes\":[\"jobs:read\"]}"))
            .andReturn();
    matches("/tokens", "post", 201, token);
    JsonNode issued = mapper.readTree(token.getResponse().getContentAsByteArray());
    assertThat(issued.path("secret").asText()).startsWith("clamv_");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void actualJobAndProgressPayloadsMatchStableRequiredFields() throws Exception {
    endpoints.ensureDefaultEndpoint();
    ClamdEndpoint endpoint = endpoints.defaultEndpointOrEnsure();
    ScanJob job = new ScanJob();
    job.setId("issue55-contract-job");
    job.setType(ScanJobType.UPLOAD);
    job.setTarget("sample-safe-name.txt");
    job.setEndpoint(endpoint);
    job.setStatus(ScanJobStatus.QUEUED);
    job.setSubmittedAt(Instant.now());
    jobRepo.saveAndFlush(job);
    matches(
        "/jobs/{id}",
        "get",
        200,
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/jobs/issue55-contract-job").secure(true))
            .andReturn());
    matches(
        "/jobs/{id}/progress",
        "get",
        200,
        mvc.perform(
                MockMvcRequestBuilders.get("/api/v1/jobs/issue55-contract-job/progress")
                    .secure(true))
            .andReturn());
  }
}
