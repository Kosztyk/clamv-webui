package info.trizub.clamav.webclient.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:rest-ui-restoration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.task.scheduling.enabled=false", "CLAMAV_REST_URL=",
    "clamav.bootstrap.admin.password=RestUiVerificationOnlyPass123456"
})
@AutoConfigureMockMvc
class RestUiRestorationTest {
    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles={"ADMIN"})
    void newLayoutRendersApiScanAndApiReportsAndLoadsRealAssets() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().isOk())
            .andExpect(content().string(containsString("rest-api.js")))
            .andExpect(content().string(containsString("API Scan")))
            .andExpect(content().string(containsString("API Reports")));
        mvc.perform(get("/api-scan")).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-rest-upload")));
        mvc.perform(get("/api-reports")).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-rest-filters")))
            .andExpect(content().string(containsString("data-rest-export")));
        mvc.perform(get("/js/rest-api.js")).andExpect(status().isOk());
        mvc.perform(get("/css/rest-api.css")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles={"VIEWER"})
    void viewerCanSeeServiceSummaryButNotExternalJobDataOrScanForm() throws Exception {
        mvc.perform(get("/rest-monitor/summary")).andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false));
        for (String path : new String[]{"/api-scan","/api-reports","/rest-monitor/jobs",
                "/rest-monitor/history","/rest-monitor/jobs/testjob", "/rest-monitor/analytics",
                "/rest-monitor/export", "/rest-monitor/webhooks"}) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/rest-monitor/upload")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles={"OPERATOR"})
    void operatorCanViewApiTabsButCannotManageUsers() throws Exception {
        mvc.perform(get("/api-scan")).andExpect(status().isOk());
        mvc.perform(get("/api-reports")).andExpect(status().isOk());
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }
}
