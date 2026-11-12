package info.trizub.clamav.webclient.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WatcherCoverageTest {
    @TempDir Path folder;
    final SettingsService settings=mock(SettingsService.class);
    final WatchedDirectoryRepository directories=mock(WatchedDirectoryRepository.class);
    final ProcessedFileRepository processed=mock(ProcessedFileRepository.class);
    final ScanJobService jobs=mock(ScanJobService.class);
    final ClamdEndpoint endpoint=new ClamdEndpoint("engine","localhost",3310,xyz.capybara.clamav.Platform.UNIX);
    final AtomicLong now=new AtomicLong(1_000_000);
    final Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now.get());}
        public long millis(){return now.get();}
    };
    WatcherService watcher() {
        when(settings.watchEnabled()).thenReturn(true);when(settings.watchPollSeconds()).thenReturn(30);
        when(settings.allowedRoots()).thenReturn(List.of(folder));
        when(directories.findByEnabledTrue()).thenReturn(List.of(new WatchedDirectory(folder.toString(),endpoint)));
        when(processed.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        return new WatcherService(settings,directories,processed,new WatchFileSubmissionService(jobs,processed),clock);
    }
    @Test void changedFileFailurePreservesCommittedFingerprintAndRetriesWithAWarnPath() throws Exception {
        Path file=Files.writeString(folder.resolve("changed.txt"),"new larger contents");
        var previous=new ProcessedFile(file.toString(),1,1,null);
        when(processed.findByPathStartingWith(anyString())).thenReturn(List.of(previous));
        doThrow(new IllegalStateException("synthetic failure")).doReturn(new ScanJob()).when(jobs).createWatchFileJob(file,endpoint,"watcher");
        var logger=(Logger)LoggerFactory.getLogger(WatcherService.class);var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try {
            var watcher=watcher();watcher.poll();
            assertThat(previous.getSizeBytes()).isEqualTo(1);assertThat(previous.getLastModified()).isEqualTo(1);
            verify(processed,never()).saveAndFlush(any());
            assertThat(events.list).anySatisfy(event->{assertThat(event.getLevel()).isEqualTo(Level.WARN);assertThat(event.getFormattedMessage()).contains(file.toString(),"retry");});
            now.addAndGet(30_000);watcher.poll();
            verify(jobs,times(2)).createWatchFileJob(file,endpoint,"watcher");
            long length=Files.size(file);
            verify(processed).saveAndFlush(argThat(marker->marker!=previous && marker.getSizeBytes()==length));
            verify(processed,never()).findByPath(anyString());
        } finally {logger.detachAppender(events);events.stop();}
    }
    @Test void traversalIncludesFilesDeeperThanTheOldFiveLevelLimit() throws Exception {
        Path nested=Files.createDirectories(folder.resolve("a/b/c/d/e/f/g/h"));Path file=Files.writeString(nested.resolve("deep.txt"),"deep");
        watcher().poll();verify(jobs).createWatchFileJob(file,endpoint,"watcher");
        verify(processed).findByPathStartingWith(folder+folder.getFileSystem().getSeparator());
        verify(processed,never()).findByPath(anyString());
    }
    @Test void aFailedFileDoesNotPreventOtherFilesFromBeingSubmitted() throws Exception {
        Path failed=Files.writeString(folder.resolve("a.txt"),"a"),good=Files.writeString(folder.resolve("b.txt"),"b");
        doThrow(new IllegalArgumentException("policy unavailable")).when(jobs).createWatchFileJob(failed,endpoint,"watcher");
        watcher().poll();verify(jobs).createWatchFileJob(good,endpoint,"watcher");
        verify(processed,times(1)).saveAndFlush(argThat(marker->marker.getPath().equals(good.toString())));
    }
    @Test void symlinkFilesAreCheckedAgainstRealRootsAndDirectoryLinksAreNotTraversed() throws Exception {
        Path inside=Files.writeString(folder.resolve("inside.txt"),"inside");
        Path outside=Files.createTempDirectory("watcher-outside-");
        try {
            Path secret=Files.writeString(outside.resolve("outside.txt"),"outside");
            Path bad=Files.createSymbolicLink(folder.resolve("escape.txt"),secret);
            Path good=Files.createSymbolicLink(folder.resolve("safe-link.txt"),inside);
            Files.createSymbolicLink(folder.resolve("directory-link"),outside);
            watcher().poll();verify(jobs).createWatchFileJob(inside,endpoint,"watcher");verify(jobs).createWatchFileJob(good,endpoint,"watcher");
            verify(jobs,never()).createWatchFileJob(eq(bad),any(),anyString());
            verify(jobs,times(2)).createWatchFileJob(any(),any(),anyString());
        } finally {Files.deleteIfExists(outside.resolve("outside.txt"));Files.deleteIfExists(outside);}
    }
    @Test void twoConcurrentPollsCannotSubmitTheSameSnapshot() throws Exception {
        Path file=Files.writeString(folder.resolve("concurrent.txt"),"data");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return new ScanJob();})
            .when(jobs).createWatchFileJob(file,endpoint,"watcher");
        var watcher=watcher();var executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> first=executor.submit(watcher::poll);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            now.addAndGet(60_000);watcher.poll();release.countDown();first.get(5,TimeUnit.SECONDS);
            verify(jobs,times(1)).createWatchFileJob(file,endpoint,"watcher");
        } finally {release.countDown();executor.shutdownNow();}
    }
}
