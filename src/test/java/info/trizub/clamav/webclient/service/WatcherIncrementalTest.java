package info.trizub.clamav.webclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ProcessedFile;
import info.trizub.clamav.webclient.model.WatchedDirectory;
import info.trizub.clamav.webclient.repo.ProcessedFileRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherIncrementalTest {
  @TempDir Path folder;
  private final SettingsService settings = mock(SettingsService.class);
  private final WatchedDirectoryRepository directories = mock(WatchedDirectoryRepository.class);
  private final ProcessedFileRepository processed = mock(ProcessedFileRepository.class);
  private final WatchFileSubmissionService submissions = mock(WatchFileSubmissionService.class);
  private final ClamdEndpoint endpoint =
      new ClamdEndpoint("test", "localhost", 3310, xyz.capybara.clamav.Platform.UNIX);
  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
          return now.get();
        }
      };
  private final Map<String, ProcessedFile> durable = new HashMap<>();
  private final List<String> submittedPaths = new ArrayList<>();
  private final List<Integer> lookupSizes = new ArrayList<>();

  WatcherService watcher(List<WatchedDirectory> roots) {
    org.springframework.test.util.ReflectionTestUtils.setField(endpoint, "id", 10L);
    when(settings.watchEnabled()).thenReturn(true);
    when(settings.watchPollSeconds()).thenReturn(30);
    when(settings.allowedRoots()).thenReturn(List.of(folder));
    when(directories.findByEnabledTrue()).thenReturn(roots);
    when(processed.findByPathInAndEndpointId(anyCollection(), anyLong()))
        .thenAnswer(
            invocation -> {
              Collection<String> paths = invocation.getArgument(0);
              Long endpointId = invocation.getArgument(1);
              lookupSizes.add(paths.size());
              return paths.stream()
                  .map(path -> durable.get(path + "#" + endpointId))
                  .filter(java.util.Objects::nonNull)
                  .toList();
            });
    when(submissions.submit(any(Path.class), any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              Path file = invocation.getArgument(0);
              long modified = invocation.getArgument(3), size = invocation.getArgument(4);
              String path = file.toAbsolutePath().normalize().toString();
              ClamdEndpoint assigned = invocation.getArgument(1);
              ProcessedFile marker =
                  new ProcessedFile(path, modified, size, null, assigned.getId());
              marker.assignSubmission("fixture-" + submittedPaths.size());
              durable.put(path + "#" + assigned.getId(), marker);
              submittedPaths.add(path);
              return marker;
            });
    return new WatcherService(settings, directories, processed, submissions, clock);
  }

  @Test
  void thousandsOfFilesAreProcessedAcrossTicksWithAtMost128MarkerKeysPerQuery() throws Exception {
    for (int n = 0; n < 1030; n++)
      Files.writeString(folder.resolve(String.format("file-%05d", n)), "a");
    WatcherService watcher = watcher(List.of(new WatchedDirectory(folder.toString(), endpoint)));
    watcher.poll();
    int firstTick = submittedPaths.size();
    assertThat(firstTick).isBetween(1, WatcherService.ENTRIES_PER_TICK);
    watcher.poll();
    assertThat(submittedPaths.size())
        .isGreaterThan(firstTick)
        .isLessThanOrEqualTo(2 * WatcherService.ENTRIES_PER_TICK);
    for (int n = 0; n < 64 && submittedPaths.size() < 1030; n++) watcher.poll();
    assertThat(submittedPaths).hasSize(1030).doesNotHaveDuplicates();
    assertThat(lookupSizes)
        .isNotEmpty()
        .allSatisfy(size -> assertThat(size).isBetween(1, WatcherService.LOOKUP_BATCH_SIZE));
    watcher.shutdown();
  }

  @Test
  void completedCycleWaitsForConfiguredIntervalThenDetectsNewFile() throws Exception {
    Path first = Files.writeString(folder.resolve("existing.txt"), "1");
    WatcherService watcher = watcher(List.of(new WatchedDirectory(folder.toString(), endpoint)));
    watcher.poll();
    assertThat(submittedPaths).containsExactly(first.toString());
    Path second = Files.writeString(folder.resolve("later.txt"), "2");
    now.addAndGet(1000);
    watcher.poll();
    assertThat(submittedPaths).hasSize(1);
    now.addAndGet(30_000);
    watcher.poll();
    assertThat(submittedPaths).containsExactlyInAnyOrder(first.toString(), second.toString());
    // Committed fingerprints are not submitted again during a new cycle.
    assertThat(submittedPaths).doesNotHaveDuplicates();
    watcher.shutdown();
  }

  @Test
  void largeDirectoryDoesNotStarveSmallOtherRoot() throws Exception {
    Path large = Files.createDirectory(folder.resolve("a-large"));
    Path small = Files.createDirectory(folder.resolve("b-small"));
    for (int n = 0; n < 600; n++)
      Files.writeString(large.resolve(String.format("entry-%05d", n)), "1");
    Path smallFile = Files.writeString(small.resolve("one.txt"), "1");
    WatcherService watcher =
        watcher(
            List.of(
                new WatchedDirectory(large.toString(), endpoint),
                new WatchedDirectory(small.toString(), endpoint)));
    watcher.poll();
    assertThat(submittedPaths.size()).isBetween(1, WatcherService.ENTRIES_PER_TICK);
    watcher.poll();
    assertThat(submittedPaths).contains(smallFile.toString());
    for (int n = 0; n < 64 && submittedPaths.size() < 601; n++) watcher.poll();
    assertThat(submittedPaths).hasSize(601).doesNotHaveDuplicates();
    watcher.shutdown();
  }

  @Test
  void completedSmallRootRescansWhileHugeRootIsStillIncomplete() throws Exception {
    Path huge = Files.createDirectory(folder.resolve("a-huge"));
    Path small = Files.createDirectory(folder.resolve("b-small"));
    for (int n = 0; n < 2000; n++) Files.writeString(huge.resolve("entry-" + n), "data");
    Path first = Files.writeString(small.resolve("initial.txt"), "data");
    WatcherService watcher =
        watcher(
            List.of(
                new WatchedDirectory(huge.toString(), endpoint),
                new WatchedDirectory(small.toString(), endpoint)));
    for (int tick = 0; tick < 4 && !submittedPaths.contains(first.toString()); tick++)
      watcher.poll();
    assertThat(submittedPaths).contains(first.toString());
    assertThat(submittedPaths.size()).isLessThan(2001);
    Path added = Files.writeString(small.resolve("new.txt"), "data");
    now.addAndGet(30_001);
    for (int tick = 0; tick < 4 && !submittedPaths.contains(added.toString()); tick++)
      watcher.poll();
    assertThat(submittedPaths).contains(added.toString());
    watcher.shutdown();
  }

  @Test
  void changingEndpointResubmitsUnchangedFileAndCreatesIndependentFingerprint() throws Exception {
    Path unchanged = Files.writeString(folder.resolve("unchanged.txt"), "data");
    WatchedDirectory watched = new WatchedDirectory(folder.toString(), endpoint);
    WatcherService watcher = watcher(List.of(watched));
    watcher.poll();
    assertThat(submittedPaths).containsExactly(unchanged.toString());
    ClamdEndpoint different =
        new ClamdEndpoint("other", "localhost", 3311, xyz.capybara.clamav.Platform.UNIX);
    org.springframework.test.util.ReflectionTestUtils.setField(different, "id", 20L);
    watched.setEndpoint(different);
    watcher.poll();
    assertThat(submittedPaths).containsExactly(unchanged.toString(), unchanged.toString());
    assertThat(durable.keySet()).contains(unchanged + "#10", unchanged + "#20");
    watcher.shutdown();
  }

  @Test
  void stoppingAnIncompleteTraversalReleasesProgressAndRescansWithoutDuplicateSubmissions()
      throws Exception {
    for (int n = 0; n < 385; n++)
      Files.writeString(folder.resolve(String.format("item-%05d", n)), "1");
    WatcherService watcher = watcher(List.of(new WatchedDirectory(folder.toString(), endpoint)));
    watcher.poll();
    assertThat(submittedPaths.size()).isBetween(1, WatcherService.ENTRIES_PER_TICK);
    watcher.stop();
    watcher.start();
    for (int n = 0; n < 32 && submittedPaths.size() < 385; n++) watcher.poll();
    assertThat(submittedPaths).hasSize(385).doesNotHaveDuplicates();
    watcher.shutdown();
  }

  @Test
  void aFailingMarkerBatchIsRetriedOnTheNextCompleteCycle() throws Exception {
    Path file = Files.writeString(folder.resolve("retry-after-db-error.txt"), "data");
    WatcherService watcher = watcher(List.of(new WatchedDirectory(folder.toString(), endpoint)));
    reset(processed);
    when(processed.findByPathInAndEndpointId(anyCollection(), anyLong()))
        .thenThrow(new IllegalStateException("transient DB failure"))
        .thenAnswer(invocation -> List.of());
    watcher.poll();
    assertThat(submittedPaths).isEmpty();
    now.addAndGet(30_000);
    watcher.poll();
    assertThat(submittedPaths).containsExactly(file.toString());
    watcher.shutdown();
  }
}
