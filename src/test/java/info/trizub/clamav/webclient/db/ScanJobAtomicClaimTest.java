package info.trizub.clamav.webclient.db;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:phase2claim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScanJobAtomicClaimTest {
    @Autowired ScanJobRepository repo;
    @Autowired ApplicationContext context;

    @Test void jpaSliceDoesNotInstantiateWebControllersOrScanServices() {
        assertFalse(context.containsBean("apiController"));
        assertFalse(context.containsBean("scanJobService"));
    }

    @Test void aQueuedJobCanBeClaimedExactlyOnce() {
        ScanJob job = new ScanJob();
        job.setId("atomic-claim-test");
        job.setType(ScanJobType.UPLOAD);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget("test-clean-file.txt");
        repo.saveAndFlush(job);
        assertEquals(1, repo.claimQueued(job.getId(), ScanJobStatus.QUEUED,
                ScanJobStatus.RUNNING, Instant.now()));
        assertEquals(0, repo.claimQueued(job.getId(), ScanJobStatus.QUEUED,
                ScanJobStatus.RUNNING, Instant.now()));
        assertEquals(ScanJobStatus.RUNNING, repo.findById(job.getId()).orElseThrow().getStatus());
    }
}
