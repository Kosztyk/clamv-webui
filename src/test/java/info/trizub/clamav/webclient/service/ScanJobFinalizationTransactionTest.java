package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.metrics.ScanMetrics;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Verify that finish operations enter a real Spring transaction proxy. */
@SpringJUnitConfig(classes = ScanJobFinalizationTransactionTest.Config.class)
class ScanJobFinalizationTransactionTest {
  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Config {
    @Bean
    ScanJobRepository jobs() {
      return mock(ScanJobRepository.class);
    }

    @Bean
    AuditService audit() {
      return mock(AuditService.class);
    }

    @Bean
    ScanMetrics metrics() {
      return mock(ScanMetrics.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    DataSource dataSource() {
      DriverManagerDataSource source = new DriverManagerDataSource();
      source.setDriverClassName("org.h2.Driver");
      source.setUrl("jdbc:h2:mem:scan-finalization-transaction;DB_CLOSE_DELAY=-1");
      return source;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    ScanJobFinalizationService finalizer(
        ScanJobRepository db, ObjectMapper mapper, AuditService audit, ScanMetrics metrics) {
      return new ScanJobFinalizationService(db, mapper, audit, metrics);
    }
  }

  @Autowired ScanJobFinalizationService finalizer;
  @Autowired ScanJobRepository jobs;
  @Autowired ScanMetrics metrics;
  @Autowired AuditService audit;

  @Test
  void finalizationCommitsThroughTheProxy() {
    assertThat(AopUtils.isAopProxy(finalizer)).isTrue();
    var job = new ScanJob();
    job.setId("transaction-ok");
    job.setStatus(ScanJobStatus.RUNNING);
    when(jobs.findById("transaction-ok"))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return Optional.of(job);
            });
    finalizer.finishOk(job.getId());
    verify(jobs).save(job);
    verify(metrics).recordCompletion(job);
    verify(audit).recordResult(job);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void persistenceFailureRollsBackInsteadOfCommitting() {
    var job = new ScanJob();
    job.setId("transaction-fail");
    job.setStatus(ScanJobStatus.RUNNING);
    when(jobs.findById("transaction-fail"))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return Optional.of(job);
            });
    when(jobs.save(job)).thenThrow(new IllegalStateException("db failure"));
    assertThatThrownBy(() -> finalizer.finishError(job.getId(), "error"))
        .isInstanceOf(IllegalStateException.class);
    verify(metrics, never()).recordCompletion(job);
    verify(audit, never()).recordResult(job);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void independentQuarantineFailureSurvivesPermissionRepair() {
    var job = new ScanJob();
    job.setId("mixed-quarantine");
    job.setStatus(ScanJobStatus.FINISHED);
    job.setVerdict(ScanVerdict.VIRUS_FOUND);
    job.setErrorMessage("Partial scan: one file unreadable\n" + QuarantineService.SOURCE_FAILURE);
    when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
    finalizer.reconcileQuarantineWarning(job.getId(), true);
    assertThat(job.getErrorMessage())
        .contains("Partial scan: one file unreadable", QuarantineService.SOURCE_FAILURE);
    verify(jobs).saveAndFlush(job);
  }

  @Test
  void positiveStagingPersistsQuarantineIntentBeforeFilesMove() {
    var job = new ScanJob();
    job.setId("stage-with-identity");
    job.setStatus(ScanJobStatus.RUNNING);
    when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
    finalizer.stageDetections(
        job.getId(),
        java.util.Map.of("/var/tmp/infected", java.util.List.of("A")),
        null,
        true,
        true);
    assertThat(job.getScanTargetWasDirectory()).isTrue();
    assertThat(job.getQuarantineRequested()).isTrue();
    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
    verify(jobs).saveAndFlush(job);
  }
}
