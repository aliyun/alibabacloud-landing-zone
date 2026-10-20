import type {
  RuntimeFailureDiagnostic,
  RuntimeFailureHistory,
  RuntimeFailureRound,
  RuntimeTraceEvent,
} from '@/shared/types/workitem';

function recordOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function bool(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function defined(value: object): boolean {
  return Object.values(value).some((entry) => {
    if (entry === undefined) return false;
    return Array.isArray(entry) ? entry.length > 0 : true;
  });
}

function diagnosticOf(value: unknown): RuntimeFailureDiagnostic | null {
  const record = recordOf(value);
  if (!record) return null;
  const diagnostic: RuntimeFailureDiagnostic = {
    timestamp: text(record.timestamp),
    source: text(record.source),
    category: text(record.category),
    code: text(record.code),
    subCode: text(record.subCode),
    businessCode: text(record.businessCode),
    httpStatus: count(record.httpStatus),
    message: text(record.message),
    requestId: text(record.requestId),
    serverRequestId: text(record.serverRequestId),
    retryable: bool(record.retryable),
    willRetry: bool(record.willRetry),
    occurrences: count(record.occurrences),
    firstSeen: text(record.firstSeen),
    lastSeen: text(record.lastSeen),
  };
  return defined(diagnostic) ? diagnostic : null;
}

function roundOf(value: unknown): RuntimeFailureRound | null {
  const record = recordOf(value);
  if (!record) return null;
  const diagnostics = (Array.isArray(record.diagnostics) ? record.diagnostics : [])
    .map(diagnosticOf)
    .filter((diagnostic): diagnostic is RuntimeFailureDiagnostic => diagnostic !== null);
  const round: RuntimeFailureRound = {
    phase: text(record.phase),
    attemptIndex: count(record.attemptIndex),
    provider: text(record.provider),
    model: text(record.model),
    sessionId: text(record.sessionId),
    startedAt: text(record.startedAt),
    endedAt: text(record.endedAt),
    durationMs: count(record.durationMs),
    status: text(record.status),
    error: text(record.error),
    diagnostics,
    diagnosticsTruncated: bool(record.diagnosticsTruncated),
    omittedDiagnostics: count(record.omittedDiagnostics),
  };
  return defined(round) ? round : null;
}

/** Parses the bounded failureHistory payload; malformed rounds are dropped, never guessed. */
export function parseFailureHistory(value: unknown): RuntimeFailureHistory | null {
  const record = recordOf(value);
  if (!record) return null;
  const rounds = (Array.isArray(record.rounds) ? record.rounds : [])
    .map(roundOf)
    .filter((round): round is RuntimeFailureRound => round !== null);
  if (!rounds.length) return null;
  return {
    rounds,
    truncated: bool(record.truncated),
    omittedRounds: count(record.omittedRounds),
  };
}

/**
 * The terminal runtime event carries the full bounded history while
 * intermediate events carry the rounds observed so far, so the newest
 * history-bearing event wins.
 */
export function failureHistoryOf(events?: RuntimeTraceEvent[] | null): RuntimeFailureHistory | null {
  if (!events) return null;
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const history = parseFailureHistory(events[index]?.detail?.failureHistory);
    if (history) return history;
  }
  return null;
}
