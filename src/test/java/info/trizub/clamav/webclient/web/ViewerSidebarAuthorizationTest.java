package info.trizub.clamav.webclient.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The visible UI must not advertise routes rejected by the same role policy. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:viewer-sidebar;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.task.scheduling.enabled=false",
    "clamav.bootstrap.admin.password=SidebarRegressionOnlyPassword123456"
})
@AutoConfigureMockMvc
class ViewerSidebarAuthorizationTest {
    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(username="ldap-viewer", roles={"VIEWER"})
    void viewerDashboardKeepsMonitorNavigationButHidesOperatorLinks() throws Exception {
        mvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/dashboard\"")))
            .andExpect(content().string(containsString("href=\"/main\"")))
            .andExpect(content().string(containsString("href=\"/clamdtop\"")))
            .andExpect(content().string(not(containsString("href=\"/scan\""))))
            .andExpect(content().string(not(containsString("href=\"/jobs"))))
            .andExpect(content().string(not(containsString("href=\"/api-scan\""))))
            .andExpect(content().string(not(containsString("href=\"/api-reports\""))))
            .andExpect(content().string(not(containsString("href=\"/admin/settings\""))));
    }

    @Test
    @WithMockUser(username="ldap-viewer", roles={"VIEWER"})
    void viewerCannotDirectlyOpenOperatorPages() throws Exception {
        mvc.perform(get("/scan")).andExpect(status().isForbidden());
        mvc.perform(get("/jobs")).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/unknown")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username="ldap-operator", roles={"VIEWER", "OPERATOR"})
    void operatorSeesAndCanOpenScanAndJobPages() throws Exception {
        mvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/scan\"")))
            .andExpect(content().string(containsString("href=\"/jobs\"")));
        mvc.perform(get("/scan")).andExpect(status().isOk());
        mvc.perform(get("/jobs")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username="local-admin", roles={"VIEWER", "OPERATOR", "ADMIN"})
    void administratorRetainsScanAndAuthenticationNavigation() throws Exception {
        mvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("href=\"/scan\"")))
            .andExpect(content().string(containsString("href=\"/jobs\"")))
            .andExpect(content().string(containsString("href=\"/admin/settings/authentication\"")));
    }
}
