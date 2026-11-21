package info.trizub.clamav.webclient.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.DaemonScanPolicy;
import info.trizub.clamav.webclient.service.EndpointService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class UploadAdmissionFilterTest {
  private final EndpointService endpoints = mock(EndpointService.class);
  private final DaemonScanPolicy policy = mock(DaemonScanPolicy.class);
  private final UploadAdmissionFilter filter =
      new UploadAdmissionFilter(10_000_000L, endpoints, policy);

  @AfterEach
  void clearAuth() {
    SecurityContextHolder.clearContext();
  }

  private void operator() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "operator", "N/A", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
  }

  private MockHttpServletRequest request(String uri, String query, long bytes) {
    var request =
        new MockHttpServletRequest("POST", uri) {
          @Override
          public long getContentLengthLong() {
            return bytes;
          }
        };
    request.setQueryString(query);
    request.setContentType("multipart/form-data; boundary=source-issue-30");
    return request;
  }

  @Test
  void rejectsDeclaredLargeRequestBeforeMultipartBodyIsAccessed() throws Exception {
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request("/api/v1/scans/uploads", null, 20_000_000L), response, chain);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("upload_too_large");
    assertThat(chain.getRequest()).isNull();
    verifyNoInteractions(policy);
  }

  @Test
  void restMonitorUploadAlsoRejectsOversizedBodyBeforeMultipartParsing() throws Exception {
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request("/rest-monitor/upload", null, 20_000_000L), response, chain);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentType()).contains("application/json");
    assertThat(response.getContentAsString()).contains("upload_too_large");
    assertThat(chain.getRequest()).isNull();
    verifyNoInteractions(endpoints, policy);
    var allowed = new MockFilterChain();
    filter.doFilter(
        request("/rest-monitor/upload", null, 2_000_000L), new MockHttpServletResponse(), allowed);
    assertThat(allowed.getRequest()).isNotNull();
  }

  @Test
  void rejectsSingleFileBeyondEndpointPolicyWithoutParsingMultipart() throws Exception {
    operator();
    ClamdEndpoint endpoint = mock(ClamdEndpoint.class);
    when(endpoints.get(2L)).thenReturn(endpoint);
    when(policy.uploadMaxBytes(endpoint)).thenReturn(1024L);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(
        request("/scan/upload", "uploadPreflightEndpointId=2&uploadFileCount=1", 2_000_000L),
        response,
        chain);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(chain.getRequest()).isNull();
    verify(policy).uploadMaxBytes(endpoint);
  }

  @Test
  void ordinaryBatchAndMissingHintAreNotRejectedIncorrectly() throws Exception {
    operator();
    ClamdEndpoint endpoint = mock(ClamdEndpoint.class);
    when(endpoints.get(2L)).thenReturn(endpoint);
    when(policy.uploadMaxBytes(endpoint)).thenReturn(1024L);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(
        request("/scan/upload", "uploadPreflightEndpointId=2&uploadFileCount=3", 3500L),
        response,
        chain);
    assertThat(chain.getRequest()).isNotNull();
    var second = new MockFilterChain();
    filter.doFilter(request("/scan/upload", null, 3500L), new MockHttpServletResponse(), second);
    assertThat(second.getRequest()).isNotNull();
  }

  @Test
  void unauthenticatedClientCannotProbeEndpointSpecificLimits() throws Exception {
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(
        request("/scan/upload", "uploadPreflightEndpointId=2&uploadFileCount=1", 250_000L),
        response,
        chain);
    assertThat(chain.getRequest()).isNotNull();
    verifyNoInteractions(endpoints, policy);
  }

  @Test
  void irrelevantPostNotSubjectToMultipartUploadFilter() throws Exception {
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request("/admin/users", null, 20_000_000L), response, chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
