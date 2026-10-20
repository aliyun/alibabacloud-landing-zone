import { useState } from 'react';
import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { CopyOutlined } from '@ant-design/icons';
import { i18n } from '@/shared/i18n';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { formatDuration } from '../duration';
import { redactDiagnostic } from './commentActivity';
import type { RuntimeFailureDiagnostic, RuntimeFailureHistory, RuntimeFailureRound } from '@/shared/types/workitem';

const headerButton: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '2px 4px',
  border: 0, background: 'transparent', color: 'inherit', cursor: 'pointer', textAlign: 'left',
};
const secondary: CSSProperties = { color: 'var(--aw-text-secondary)', fontSize: 11 };

function joined(parts: Array<string | null | undefined>): string {
  return parts.filter((part): part is string => typeof part === 'string' && part.length > 0).join(' · ');
}

function roundLabel(round: RuntimeFailureRound, index: number): string {
  return `${round.attemptIndex ?? index + 1}`;
}

function roundMeta(round: RuntimeFailureRound): string {
  return joined([
    round.phase,
    round.provider,
    round.model,
    round.durationMs != null ? formatDuration(round.durationMs) : undefined,
    round.status,
  ]);
}

function yesNo(value?: boolean | null): string | undefined {
  if (value == null) return undefined;
  return value ? i18n.t('workitem:failureHistory.yes') : i18n.t('workitem:failureHistory.no');
}

function roundText(round: RuntimeFailureRound, index: number): string {
  const lines = [
    joined([i18n.t('workitem:failureHistory.round', { index: roundLabel(round, index) }), roundMeta(round)]),
  ];
  if (round.error) lines.push(round.error);
  if (round.sessionId) lines.push(joined([i18n.t('workitem:failureHistory.sessionLabel'), round.sessionId]));
  for (const diagnostic of round.diagnostics ?? []) {
    const codes = joined([
      diagnostic.source, diagnostic.category, diagnostic.code,
      diagnostic.subCode, diagnostic.businessCode,
    ]);
    const occurrence = diagnostic.occurrences != null && diagnostic.occurrences > 1
      ? ` (${i18n.t('workitem:failureHistory.occurrences', { count: diagnostic.occurrences })})` : '';
    lines.push(`${joined([codes, diagnostic.message ?? undefined])}${occurrence}`);
    const identifiers = joined([
      diagnostic.requestId ? joined([i18n.t('workitem:failureHistory.requestIdLabel'), diagnostic.requestId]) : undefined,
      diagnostic.serverRequestId ? joined([i18n.t('workitem:failureHistory.serverRequestIdLabel'), diagnostic.serverRequestId]) : undefined,
      diagnostic.retryable != null ? joined([i18n.t('workitem:failureHistory.retryableLabel'), yesNo(diagnostic.retryable)]) : undefined,
    ]);
    if (identifiers) lines.push(identifiers);
  }
  return lines.join('\n');
}

function Field({ label, value }: { label: string; value?: string | number | null }) {
  if (value === undefined || value === null || value === '') return null;
  return <div style={{ display: 'flex', gap: 6, fontSize: 11 }}>
    <span style={{ ...secondary, flex: '0 0 auto', minWidth: 92 }}>{label}</span>
    <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{value}</span>
  </div>;
}

function DiagnosticItem({ diagnostic, index, open, onToggle }: {
  diagnostic: RuntimeFailureDiagnostic; index: number; open: boolean; onToggle: () => void;
}) {
  const { t } = useTranslation('workitem');
  const message = redactDiagnostic(diagnostic.message ?? '');
  return <div style={{ borderTop: '1px dashed var(--aw-border)', paddingTop: 4 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <button type="button" onClick={onToggle} aria-expanded={open}
        aria-label={open ? t('failureHistory.collapseDiagnostic', { index: index + 1 }) : t('failureHistory.expandDiagnostic', { index: index + 1 })}
        style={headerButton}>
        <span style={{ fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{message}</span>
        {diagnostic.occurrences != null && diagnostic.occurrences > 1 &&
          <span style={{ ...secondary, border: '1px solid var(--aw-border)', borderRadius: 8, padding: '0 6px' }}>
            {t('failureHistory.occurrences', { count: diagnostic.occurrences })}
          </span>}
      </button>
      <span style={{ flex: 1 }} />
      {diagnostic.timestamp && <span style={secondary}>{diagnostic.timestamp}</span>}
    </div>
    {open && <div style={{ margin: '4px 0 2px 4px', display: 'grid', gap: 2 }}>
      <Field label={t('failureHistory.sourceLabel')} value={diagnostic.source} />
      <Field label={t('failureHistory.categoryLabel')} value={diagnostic.category} />
      <Field label={t('failureHistory.codeLabel')} value={diagnostic.code} />
      <Field label={t('failureHistory.subCodeLabel')} value={diagnostic.subCode} />
      <Field label={t('failureHistory.businessCodeLabel')} value={diagnostic.businessCode} />
      <Field label={t('failureHistory.httpStatusLabel')} value={diagnostic.httpStatus} />
      <Field label={t('failureHistory.requestIdLabel')} value={diagnostic.requestId} />
      <Field label={t('failureHistory.serverRequestIdLabel')} value={diagnostic.serverRequestId} />
      <Field label={t('failureHistory.retryableLabel')} value={yesNo(diagnostic.retryable)} />
      <Field label={t('failureHistory.willRetryLabel')} value={yesNo(diagnostic.willRetry)} />
      <Field label={t('failureHistory.firstSeenLabel')} value={diagnostic.firstSeen} />
      <Field label={t('failureHistory.lastSeenLabel')} value={diagnostic.lastSeen} />
    </div>}
  </div>;
}

export function FailureHistoryPanel({ history, summary, testId }: {
  history: RuntimeFailureHistory; summary?: string | null; testId?: string;
}) {
  const { t } = useTranslation('workitem');
  const [openRounds, setOpenRounds] = useState<string[]>([]);
  const [openDiagnostics, setOpenDiagnostics] = useState<string[]>([]);
  const toggle = (list: string[], key: string) => list.includes(key) ? list.filter((item) => item !== key) : [...list, key];
  const rounds = history.rounds ?? [];
  const keyOf = (round: RuntimeFailureRound, index: number) => `${round.sessionId ?? ''}:${round.phase ?? ''}:${round.attemptIndex ?? index}`;
  const copyRound = (round: RuntimeFailureRound, index: number) => void copyTextToClipboard(roundText(round, index));
  return <section data-testid={testId ?? 'failure-history'}
    style={{ border: '1px solid var(--aw-border)', borderRadius: 8, background: 'var(--aw-raised)', padding: '10px 12px' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      <strong style={{ fontSize: 12 }}>{t('failureHistory.title')}</strong>
      <span style={secondary}>{t('failureHistory.roundCount', { count: rounds.length })}</span>
      {history.truncated && <span data-testid="failure-history-truncated" style={secondary}>
        {t('failureHistory.truncated', { count: history.omittedRounds ?? 0 })}
      </span>}
    </div>
    {summary?.trim() && <p data-testid="failure-history-summary"
      style={{ margin: '6px 0 0', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
      {redactDiagnostic(summary)}
    </p>}
    <ol style={{ margin: '8px 0 0', padding: 0, listStyle: 'none', display: 'grid', gap: 8 }}>
      {rounds.map((round, index) => {
        const key = keyOf(round, index);
        const open = openRounds.includes(key);
        return <li key={key} style={{ border: '1px solid var(--aw-border)', borderRadius: 8, padding: '8px 10px', background: 'var(--aw-panel)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button type="button" onClick={() => setOpenRounds(toggle(openRounds, key))} aria-expanded={open}
              aria-label={open ? t('failureHistory.collapseRound', { index: roundLabel(round, index) }) : t('failureHistory.expandRound', { index: roundLabel(round, index) })}
              style={headerButton}>
              <span style={{ fontWeight: 600, fontSize: 12 }}>{t('failureHistory.round', { index: roundLabel(round, index) })}</span>
              <span style={secondary}>{roundMeta(round)}</span>
            </button>
            <span style={{ flex: 1 }} />
            <button type="button" onClick={() => copyRound(round, index)}
              aria-label={t('failureHistory.copyRound', { index: roundLabel(round, index) })} style={headerButton}>
              <CopyOutlined aria-hidden="true" />
            </button>
          </div>
          {round.error && <p data-testid={`failure-history-round-error-${roundLabel(round, index)}`}
            style={{ margin: '6px 0 0', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {redactDiagnostic(round.error)}
          </p>}
          {open && <div style={{ marginTop: 6, display: 'grid', gap: 4 }}>
            <Field label={t('failureHistory.sessionLabel')} value={round.sessionId} />
            <Field label={t('failureHistory.startedAtLabel')} value={round.startedAt} />
            <Field label={t('failureHistory.endedAtLabel')} value={round.endedAt} />
            {(round.diagnostics ?? []).length
              ? (round.diagnostics ?? []).map((diagnostic, di) => <DiagnosticItem key={`${key}:${di}`} diagnostic={diagnostic} index={di}
                open={openDiagnostics.includes(`${key}:${di}`)}
                onToggle={() => setOpenDiagnostics(toggle(openDiagnostics, `${key}:${di}`))} />)
              : <p style={{ ...secondary, margin: 0 }}>{t('failureHistory.noDiagnostics')}</p>}
            {round.diagnosticsTruncated && <p style={{ ...secondary, margin: 0 }}>
              {t('failureHistory.diagnosticsTruncated', { count: round.omittedDiagnostics ?? 0 })}
            </p>}
          </div>}
        </li>;
      })}
    </ol>
  </section>;
}
