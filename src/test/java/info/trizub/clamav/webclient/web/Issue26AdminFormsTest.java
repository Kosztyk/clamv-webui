package info.trizub.clamav.webclient.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.*;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import xyz.capybara.clamav.Platform;

class Issue26AdminFormsTest {
  private final EndpointService endpoints = mock(EndpointService.class);
  private final UserService users = mock(UserService.class);
  private final AuditService audit = mock(AuditService.class);
  private final WebUiController controller =
      new WebUiController(
          mock(ClamdTopService.class),
          mock(SettingsService.class),
          endpoints,
          mock(ScanJobService.class),
          mock(ClamavClientProvider.class),
          audit,
          mock(AuditEventRepository.class),
          mock(AppUserRepository.class),
          users,
          mock(info.trizub.clamav.webclient.repo.ExternalIdentityRepository.class),
          mock(WatchedDirectoryRepository.class),
          mock(WebhookAddressPolicy.class),
          mock(WebhookNotificationProperties.class));

  private void defaultEndpoint() {
    ClamdEndpoint primary = new ClamdEndpoint("default", "clamav-server", 3310, Platform.UNIX);
    ReflectionTestUtils.setField(primary, "id", 99L);
    when(endpoints.defaultEndpointOrEnsure()).thenReturn(primary);
  }

  @Test
  void malformedPortGivesFlashErrorInsteadOfExceptionOrDatabaseWrite() {
    var flash = new RedirectAttributesModelMap();
    assertThat(
            controller.adminEndpointsCreate(
                "scanner", "daemon", "abc", "UNIX", true, null, null, flash))
        .isEqualTo("redirect:/admin/endpoints");
    assertThat(flash.getFlashAttributes().get("errorMsg").toString()).contains("port");
    verify(endpoints, never()).create(any(), any(), anyInt(), any(), anyBoolean());
  }

  @Test
  void badPlatformAndUnixHostGiveFlashErrorsOnCreateAndUpdate() {
    var flash = new RedirectAttributesModelMap();
    controller.adminEndpointsCreate(
        "scanner", "daemon", "3310", "unexpected", true, null, null, flash);
    assertThat(flash.getFlashAttributes().get("errorMsg").toString()).contains("platform");
    defaultEndpoint();
    var update = new RedirectAttributesModelMap();
    controller.adminEndpointsUpdate(
        15L, "scanner", "/run/clamd.sock", "3310", "UNIX", true, null, null, update);
    assertThat(update.getFlashAttributes().get("errorMsg").toString()).contains("host");
    verify(endpoints, never()).update(anyLong(), any(), any(), anyInt(), any(), anyBoolean());
  }

  @Test
  void userServiceValidationIsShownAsFlashMessage() {
    when(users.createUser(eq("invalid!"), anyString(), anySet(), eq(true)))
        .thenThrow(new IllegalArgumentException("Username must be 3–64 allowed characters"));
    var flash = new RedirectAttributesModelMap();
    assertThat(
            controller.adminUsersCreate(
                "invalid!", "some-long-password", true, "VIEWER", null, null, flash))
        .isEqualTo("redirect:/admin/users");
    assertThat(flash.getFlashAttributes().get("errorMsg").toString()).contains("Username");
    verifyNoInteractions(audit);
  }

  @Test
  void unknownRoleCannotBeSilentlyConvertedToViewer() {
    var flash = new RedirectAttributesModelMap();
    controller.adminUsersCreate("alice", "a-valid-password", true, "SUPERADMIN", null, null, flash);
    assertThat(flash.getFlashAttributes().get("errorMsg").toString()).contains("role");
    verify(users, never()).createUser(any(), any(), anySet(), anyBoolean());
  }
}
