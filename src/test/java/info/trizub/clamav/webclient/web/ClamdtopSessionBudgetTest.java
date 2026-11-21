package info.trizub.clamav.webclient.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClamdtopSessionBudgetTest {
  @Test
  void oneViewerCannotExhaustGlobalSlots() {
    var budget = new ClamdtopSessionBudget(8, 2);
    assertThat(budget.reserve("s1", "viewer")).isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
    assertThat(budget.reserve("s2", "viewer")).isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
    assertThat(budget.reserve("s3", "viewer")).isEqualTo(ClamdtopSessionBudget.Result.USER_LIMIT);
    assertThat(budget.activeCount()).isEqualTo(2);
    assertThat(budget.reserve("admin-1", "admin")).isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
    assertThat(budget.reserve("s1", "anyone")).isEqualTo(ClamdtopSessionBudget.Result.DUPLICATE);
    budget.release("s1");
    budget.release("s1"); // asynchronous disconnect + reader exit must never double-decrement
    assertThat(budget.userCount("viewer")).isEqualTo(1);
    assertThat(budget.reserve("s3", "viewer")).isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
  }

  @Test
  void atomicGlobalAndUserLimitsUnderSimultaneousConnections() throws Exception {
    var budget = new ClamdtopSessionBudget(8, 2);
    var pool = Executors.newFixedThreadPool(24);
    try {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<ClamdtopSessionBudget.Result>>();
      for (int i = 0; i < 24; i++) {
        final int n = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return budget.reserve("parallel-" + n, "viewer-" + (n % 3));
                }));
      }
      start.countDown();
      int granted = 0;
      for (var future : futures) {
        if (future.get(3, TimeUnit.SECONDS) == ClamdtopSessionBudget.Result.GRANTED) granted++;
      }
      // Only 3 distinct users, each limited to 2, even under a concurrent race.
      assertThat(granted).isEqualTo(6);
      assertThat(budget.activeCount()).isEqualTo(6);
      for (int i = 0; i < 3; i++) assertThat(budget.userCount("viewer-" + i)).isEqualTo(2);
      assertThat(budget.reserve("operator-1", "operator"))
          .isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
      assertThat(budget.reserve("operator-2", "operator"))
          .isEqualTo(ClamdtopSessionBudget.Result.GRANTED);
      assertThat(budget.reserve("admin-1", "admin"))
          .isEqualTo(ClamdtopSessionBudget.Result.GLOBAL_LIMIT);
      for (int i = 0; i < 24; i++) budget.release("parallel-" + i);
      budget.release("operator-1");
      budget.release("operator-2");
      assertThat(budget.activeCount()).isZero();
    } finally {
      pool.shutdownNow();
    }
  }
}
