package info.trizub.clamav.webclient.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.service.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** Duplicate finish invocations must not create duplicate metrics. */
class ScanCompletionBoundaryTest {
    @Test void repeatedFinishRecordsOnlyOneCompletion() {
        var repository=mock(ScanJobRepository.class);
        var metrics=mock(ScanMetrics.class);
        var audit=mock(AuditService.class);
        var job=new ScanJob(); job.setId("job-1"); job.setType(ScanJobType.UPLOAD);
        job.setStatus(ScanJobStatus.RUNNING);
        when(repository.findById("job-1")).thenReturn(Optional.of(job));
        var service = new ScanExecutionService(mock(SettingsService.class), repository,
                new ObjectMapper(), mock(ClamavClientProvider.class),
                mock(QuarantineService.class), mock(NotificationService.class), audit, metrics, mock(DaemonScanPolicy.class));
        service.finishOk("job-1");
        service.finishOk("job-1");
        verify(metrics, times(1)).recordCompletion(job);
        verify(audit,times(1)).recordResult(job);
        verify(repository,times(1)).save(job);
    }
}
