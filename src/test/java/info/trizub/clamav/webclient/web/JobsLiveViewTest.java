package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:jobs-live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "clamav.bootstrap.admin.password=JobsLiveLocalAdmin456","clamav.config.initial-delay-ms=3600000",
    "clamav.metrics.clamd-initial-delay-ms=3600000","clamav.configuration.file=target/test-config/jobs-live-${random.uuid}/center.properties"})
@AutoConfigureMockMvc
class JobsLiveViewTest {
    @Autowired MockMvc mvc;
    @Autowired ScanJobRepository repository;
    @Test void freshFragmentReflectsCommittedStatusAndVerdictAndEscapesTargets() throws Exception {
        ScanJob job=new ScanJob();job.setId(UUID.randomUUID().toString());job.setType(ScanJobType.UPLOAD);
        job.setTarget("<img src=x onerror=alert(1)>.txt");job.setStatus(ScanJobStatus.QUEUED);
        repository.saveAndFlush(job);
        try {
            mvc.perform(get("/jobs/table").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(containsString("data-job-status=\"QUEUED\"")))
                .andExpect(content().string(containsString("&lt;img")))
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(not(containsString("app-main"))));
            job.setStatus(ScanJobStatus.RUNNING);repository.saveAndFlush(job);
            mvc.perform(get("/jobs/table").with(user("operator").roles("OPERATOR")))
                .andExpect(content().string(containsString("data-job-status=\"RUNNING\"")));
            job.setStatus(ScanJobStatus.FINISHED);job.setVerdict(ScanVerdict.VIRUS_FOUND);job.setFinishedAt(Instant.now());repository.saveAndFlush(job);
            mvc.perform(get("/jobs/table").with(user("operator").roles("OPERATOR")))
                .andExpect(content().string(containsString("data-job-status=\"FINISHED\"")))
                .andExpect(content().string(containsString("VIRUS_FOUND")));
        } finally {repository.deleteById(job.getId());}
    }
    @Test void liveReadRetainsOperatorAuthorizationAndTheApplicationContextPath() throws Exception {
        mvc.perform(get("/jobs/table").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/jobs/table").accept(org.springframework.http.MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/clamav/jobs/table").contextPath("/clamav").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("/clamav/jobs/table")));
    }
}
