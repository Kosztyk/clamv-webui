#!/usr/bin/env python3
"""Offline structural checks for issue #38 (does not substitute for Maven/runtime tests)."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
WS = (ROOT / 'src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java').read_text()
BUDGET = (ROOT / 'src/main/java/info/trizub/clamav/webclient/web/ClamdtopSessionBudget.java').read_text()
JS = (ROOT / 'src/main/resources/static/js/native-clamdtop.js').read_text()
CONFIG = (ROOT / 'src/main/resources/application.properties').read_text()
TEST = (ROOT / 'src/test/java/info/trizub/clamav/webclient/web/Issue38NativeClamdtopSecurityTest.java').read_text()
LIMIT_TEST = (ROOT / 'src/test/java/info/trizub/clamav/webclient/web/ClamdtopSessionBudgetTest.java').read_text()

checks = {
    'only one active native WebSocket implementation': not (ROOT / 'src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocketHandler.java').exists(),
    'child environment explicitly cleared': 'environment.clear();' in WS,
    'exactly four process environment overrides': set(re.findall(r'environment\.put\("([A-Z_]+)"', WS)) == {'TERM', 'COLUMNS', 'LINES', 'PATH'},
    'no inherited clamd or identity environment': 'System.getenv(' not in WS and 'System.getProperties(' not in WS,
    'whitelisted child process environment tested': 'spawnedProcessCannotInheritApplicationSecrets' in TEST and '/usr/bin/env' in TEST,
    'authentication identity from session principal': 'session.getPrincipal() instanceof Authentication' in WS and 'auth.getName()' in WS,
    'global cap eight': 'MAX_CONNECTIONS = 8' in WS,
    'per-username cap two': 'MAX_PER_USER = 2' in WS,
    'admission before process startup': WS.index('budget.reserve(') < WS.index('processStarter.start('),
    'reservation guarded by synchronized lock': 'synchronized Result reserve(' in BUDGET,
    'per-user limit enforcement': 'userCounts.getOrDefault(username, 0) >= perUserLimit' in BUDGET,
    'idempotent release on teardown': 'synchronized void release(' in BUDGET and 'if (username == null) return' in BUDGET,
    'no non-atomic active-size gate': 'running.size()' not in WS,
    'close and launch serialized': 'synchronized (monitor)' in WS and 'monitor.stopped' in WS,
    'PTY children destroyed on cleanup': 'process.toHandle().descendants()' in WS,
    'client heartbeat accepted': '"heartbeat".equals(type)' in WS,
    'visible browser sends periodic heartbeat': "document.visibilityState === 'visible'" in JS and "type: 'heartbeat'" in JS,
    'hidden tabs expire and resources get reaped': '@Scheduled(fixedDelay = 30_000' in WS and 'idleTimeoutNanos' in WS,
    'idle timeout deployment env override': 'CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS:180' in CONFIG,
    'JVM shutdown closes native sessions': '@PreDestroy' in WS and 'public void shutdown()' in WS,
    'concurrent admission and per-user limit tested': 'atomicGlobalAndUserLimitsUnderSimultaneousConnections' in LIMIT_TEST and 'webSocketEnforcesTwoPerUser' in TEST,
    'failure and idle session release tested': 'failedNativeLaunchDoesNotHoldAnySessionSlots' in TEST and 'passiveMonitorHeartbeatPreventsExpiryButAbandonedTabsExpire' in TEST,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
print(f'{sum(checks.values())}/{len(checks)} offline checks passed')
sys.exit(0 if all(checks.values()) else 1)
