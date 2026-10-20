import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FailureHistoryPanel } from './FailureHistoryPanel';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import type { RuntimeFailureHistory } from '@/shared/types/workitem';

vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn().mockResolvedValue(true) }));

const incidentHistory: RuntimeFailureHistory = {
  truncated: false,
  rounds: [
    {
      phase: 'routing_preflight', attemptIndex: 1, provider: 'qoder', model: 'dmodel',
      sessionId: '689186f5-a6bd-4816-9fa1-6bff68e7b896',
      startedAt: '2026-10-15T03:11:09.100Z', endedAt: '2026-10-15T03:21:07.331Z', durationMs: 598231,
      status: 'failed', error: 'qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed',
      diagnostics: [{
        timestamp: '2026-10-15T03:21:07.331Z', source: 'provider_log', category: 'business',
        code: '10605', subCode: 'model_queued', businessCode: 'RESOURCE_LIMIT', httpStatus: 200,
        message: 'Queuing failed', requestId: 'f203f510-c793-4e82-b64d-18c879abb08c',
        serverRequestId: 'e0a61fb9-725f-4b21-b9ac-ce708c58c054', retryable: true, willRetry: true,
        occurrences: 2, firstSeen: '2026-10-15T03:21:07.331Z', lastSeen: '2026-10-15T03:21:08.140Z',
      }],
    },
    {
      phase: 'routing_preflight', attemptIndex: 2, provider: 'qoder', model: 'dmodel',
      sessionId: 'aa2db1e7-4256-49eb-ae7b-6faea387b7e0', status: 'failed',
      error: 'qoder semantic inactivity timeout after 10m0s',
      diagnostics: [{
        source: 'provider_rpc', category: 'timeout', code: 'timeout',
        message: 'no first payload within 10m0s', requestId: 'a3203242-026f-43ab-b92b-1ccc700155c4',
        serverRequestId: '345785b7-b5b3-4c9f-b250-a0bd39f770b3',
      }],
    },
  ],
};

describe('FailureHistoryPanel', () => {
  beforeEach(() => { vi.mocked(copyTextToClipboard).mockClear(); });
  afterEach(() => cleanup());

  it('summarizes and lists both incident rounds with model, duration, status and final error', () => {
    render(<FailureHistoryPanel history={incidentHistory} testId="failure-history-panel" />);
    const panel = screen.getByTestId('failure-history-panel');
    expect(within(panel).getByText('失败历史')).toBeInTheDocument();
    expect(within(panel).getByText('共 2 轮失败')).toBeInTheDocument();
    expect(within(panel).getByText('第 1 轮')).toBeInTheDocument();
    expect(within(panel).getByText('第 2 轮')).toBeInTheDocument();
    expect(within(panel).getAllByText(/routing_preflight · qoder · dmodel .* failed/)).toHaveLength(2);
    expect(within(panel).getByText(/RESOURCE_LIMIT 10605 model_queued Queuing failed/)).toBeInTheDocument();
    expect(within(panel).getByText(/semantic inactivity timeout after 10m0s/)).toBeInTheDocument();
    expect(within(panel).queryByText('model_queued')).not.toBeInTheDocument();
  });

  it('reveals codes, ids, source and duplicate counts only when expanded', () => {
    render(<FailureHistoryPanel history={incidentHistory} />);
    expect(screen.queryByText('10605')).not.toBeInTheDocument();
    const roundToggle = screen.getByRole('button', { name: '展开第 1 轮失败详情' });
    expect(roundToggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(roundToggle);
    expect(screen.getByRole('button', { name: '收起第 1 轮失败详情' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('689186f5-a6bd-4816-9fa1-6bff68e7b896')).toBeInTheDocument();
    expect(screen.getByText('重复 2 次')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '展开第 1 条诊断' }));
    expect(screen.getByText('10605')).toBeInTheDocument();
    expect(screen.getByText('model_queued')).toBeInTheDocument();
    expect(screen.getByText('RESOURCE_LIMIT')).toBeInTheDocument();
    expect(screen.getByText('provider_log')).toBeInTheDocument();
    expect(screen.getByText('business')).toBeInTheDocument();
    expect(screen.getByText('f203f510-c793-4e82-b64d-18c879abb08c')).toBeInTheDocument();
    expect(screen.getByText('e0a61fb9-725f-4b21-b9ac-ce708c58c054')).toBeInTheDocument();
    expect(screen.getAllByText('是')).toHaveLength(2);
    expect(screen.getByText('Queuing failed')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '收起第 1 条诊断' }));
    expect(screen.queryByText('10605')).not.toBeInTheDocument();
  });

  it('renders provider text as plain text and never as markup', () => {
    render(<FailureHistoryPanel history={{ rounds: [{
      attemptIndex: 1, status: 'failed', error: '<img src="x" onerror="alert(1)"> provider said <b>no</b>',
      diagnostics: [{ message: '<script>alert(1)</script> denied' }],
    }] }} testId="plain" />);
    const panel = screen.getByTestId('plain');
    expect(panel.textContent).toContain('<img src="x" onerror="alert(1)"> provider said <b>no</b>');
    expect(panel.querySelector('img')).toBeNull();
    expect(panel.querySelector('script')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '展开第 1 轮失败详情' }));
    expect(panel.querySelector('script')).toBeNull();
    expect(panel.textContent).toContain('<script>alert(1)</script> denied');
  });

  it('copies a round as plain text including identifiers from the keyboard reachable control', () => {
    render(<FailureHistoryPanel history={incidentHistory} />);
    const copy = screen.getByRole('button', { name: '复制第 1 轮失败详情' });
    expect(copy.tagName).toBe('BUTTON');
    fireEvent.click(copy);
    expect(copyTextToClipboard).toHaveBeenCalledTimes(1);
    const text = vi.mocked(copyTextToClipboard).mock.calls[0][0];
    expect(text).toContain('第 1 轮');
    expect(text).toContain('qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed');
    expect(text).toContain('f203f510-c793-4e82-b64d-18c879abb08c');
    expect(text).toContain('e0a61fb9-725f-4b21-b9ac-ce708c58c054');
    expect(text).toContain('重复 2 次');
  });

  it('surfaces truncation markers only when the bounded payload reports omissions', () => {
    const { unmount } = render(<FailureHistoryPanel history={{
      truncated: true, omittedRounds: 3,
      rounds: [{ attemptIndex: 9, status: 'failed', error: 'kept', diagnosticsTruncated: true, omittedDiagnostics: 4 }],
    }} />);
    expect(screen.getByTestId('failure-history-truncated')).toHaveTextContent('更早的 3 轮失败已省略');
    fireEvent.click(screen.getByRole('button', { name: '展开第 9 轮失败详情' }));
    expect(screen.getByText('该轮另有 4 条诊断已省略')).toBeInTheDocument();
    unmount();
    render(<FailureHistoryPanel history={incidentHistory} />);
    expect(screen.queryByTestId('failure-history-truncated')).not.toBeInTheDocument();
  });

  it('renders the legacy summary text above the rounds only when provided', () => {
    const wrapped = 'workflow routing preflight failed: qoder semantic inactivity timeout after 10m0s';
    const { unmount } = render(<FailureHistoryPanel history={incidentHistory} summary={wrapped} />);
    expect(screen.getByTestId('failure-history-summary')).toHaveTextContent(wrapped);
    unmount();
    render(<FailureHistoryPanel history={incidentHistory} />);
    expect(screen.queryByTestId('failure-history-summary')).not.toBeInTheDocument();
  });

  it('reports a round without diagnostics instead of fabricating details', () => {
    render(<FailureHistoryPanel history={{ rounds: [{ attemptIndex: 1, status: 'failed', error: 'plain failure' }] }} />);
    fireEvent.click(screen.getByRole('button', { name: '展开第 1 轮失败详情' }));
    expect(screen.getByText('该轮没有结构化诊断')).toBeInTheDocument();
  });
});
