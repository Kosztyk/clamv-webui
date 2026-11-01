package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WatcherConfigurationTest {
    @TempDir Path folder;
    final SettingsService settings=mock(SettingsService.class);
    final WatchedDirectoryRepository directories=mock(WatchedDirectoryRepository.class);
    final ProcessedFileRepository processed=mock(ProcessedFileRepository.class);
    final ScanJobService jobs=mock(ScanJobService.class);
    final DaemonScanPolicy policy=mock(DaemonScanPolicy.class);
    final AtomicLong now=new AtomicLong(1_000_000);
    final Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now.get());}
        public long millis(){return now.get();}
    };
    @Test void liveIntervalChangesRunAtTheSelectedTimeRatherThanMultiplesOfThirtySeconds() {
        when(settings.watchEnabled()).thenReturn(true);when(settings.watchPollSeconds()).thenReturn(45);
        when(directories.findByEnabledTrue()).thenReturn(List.of());
        var watcher=new WatcherService(settings,directories,processed,jobs,policy,clock);
        watcher.poll();verify(directories,times(1)).findByEnabledTrue();
        now.addAndGet(44_000);watcher.poll();verify(directories,times(1)).findByEnabledTrue();
        now.addAndGet(1_000);watcher.poll();verify(directories,times(2)).findByEnabledTrue();
        when(settings.watchPollSeconds()).thenReturn(30);now.addAndGet(30_000);watcher.poll();
        verify(directories,times(3)).findByEnabledTrue();
        when(settings.watchEnabled()).thenReturn(false);now.addAndGet(90_000);watcher.poll();
        verify(directories,times(3)).findByEnabledTrue();
    }
    @Test void disabledEndpointsAndFailedEnqueuesDoNotConsumeFilesBeforeTheyCanBeScanned() throws Exception {
        Path file=Files.writeString(folder.resolve("pending.txt"),"pending");
        var endpoint=new ClamdEndpoint("engine","localhost",3310,xyz.capybara.clamav.Platform.UNIX);endpoint.setEnabled(false);
        var watched=new WatchedDirectory();watched.setPath(folder.toString());watched.setEndpoint(endpoint);watched.setEnabled(true);
        when(settings.watchEnabled()).thenReturn(true);when(settings.watchPollSeconds()).thenReturn(30);
        when(settings.allowedRoots()).thenReturn(List.of(folder));when(directories.findByEnabledTrue()).thenReturn(List.of(watched));
        var watcher=new WatcherService(settings,directories,processed,jobs,policy,clock);
        watcher.poll();verifyNoInteractions(jobs,processed);
        endpoint.setEnabled(true);now.addAndGet(30_000);
        doThrow(new IllegalStateException("queue unavailable")).when(jobs).createWatchFileJob(file,endpoint,"watcher");
        watcher.poll();verify(processed,never()).save(any());
        reset(jobs);now.addAndGet(30_000);watcher.poll();
        var order=inOrder(jobs,processed);order.verify(jobs).createWatchFileJob(file,endpoint,"watcher");order.verify(processed).save(any());
    }
    @Test void schedulingTickIsFineEnoughForAllSupportedIntervals() throws Exception {
        assertThat(WatcherService.class.getMethod("poll").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedDelay()).isEqualTo(1000);
    }
}
