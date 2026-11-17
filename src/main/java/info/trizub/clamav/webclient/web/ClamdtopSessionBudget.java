package info.trizub.clamav.webclient.web;

import java.util.HashMap;
import java.util.Map;

/**
 * One reservation per WebSocket session, with atomic global and per-principal limits. Reserve
 * BEFORE starting a process and release on every failure, exit, or disconnect. All callers share
 * this object; do not check ConcurrentHashMap.size() outside the lock.
 */
final class ClamdtopSessionBudget {
  enum Result {
    GRANTED,
    USER_LIMIT,
    GLOBAL_LIMIT,
    DUPLICATE
  }

  private final int globalLimit;
  private final int perUserLimit;
  private final Map<String, String> owners = new HashMap<>();
  private final Map<String, Integer> userCounts = new HashMap<>();

  ClamdtopSessionBudget(int globalLimit, int perUserLimit) {
    if (globalLimit < 1 || perUserLimit < 1 || perUserLimit > globalLimit) {
      throw new IllegalArgumentException("Invalid clamdtop session limits");
    }
    this.globalLimit = globalLimit;
    this.perUserLimit = perUserLimit;
  }

  synchronized Result reserve(String sessionId, String username) {
    if (owners.containsKey(sessionId)) return Result.DUPLICATE;
    if (userCounts.getOrDefault(username, 0) >= perUserLimit) return Result.USER_LIMIT;
    if (owners.size() >= globalLimit) return Result.GLOBAL_LIMIT;
    owners.put(sessionId, username);
    userCounts.merge(username, 1, Integer::sum);
    return Result.GRANTED;
  }

  synchronized void release(String sessionId) {
    String username = owners.remove(sessionId);
    if (username == null) return; // idempotent across read, close, error, and reaper
    userCounts.computeIfPresent(username, (key, count) -> count == 1 ? null : count - 1);
  }

  synchronized int activeCount() {
    return owners.size();
  }

  synchronized int userCount(String username) {
    return userCounts.getOrDefault(username, 0);
  }
}
