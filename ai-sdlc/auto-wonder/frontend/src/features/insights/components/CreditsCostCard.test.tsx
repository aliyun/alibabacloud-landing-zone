import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { CreditsCostCard } from './CreditsCostCard';
import { i18n } from '@/shared/i18n';
import type { CostMetrics } from '../types';

const cost: CostMetrics = {
  totalTokens: 100000,
  avgTokensPerTask: 5000,
  dailyAvg: 14000,
  trend: [1, 2, 3],
  totalCredits: 12.5,
  avgCreditsPerTask: 6.25,
  dailyAvgCredits: 0.42,
  creditsTrend: [1, 2, 3],
};

const costWithWorkers: CostMetrics = {
  ...cost,
  byWorker: [
    { agentId: 100, agentName: 'DEV', credits: 8, conversationCredits: 2 },
    { agentId: 101, agentName: 'CR', credits: 4.5, conversationCredits: 0 },
    { agentId: 102, agentName: 'IDLE', credits: 0, conversationCredits: 0 },
  ],
};

async function waitPastHoverDelay() {
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 300)); });
}

async function openBreakdown() {
  fireEvent.mouseEnter(screen.getByText('总 Credits'));
  return screen.findByTestId('insights-credits-breakdown');
}

beforeEach(async () => { await i18n.changeLanguage('zh-CN'); });
afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

describe('CreditsCostCard', () => {
  it('shows the per-worker credits, the conversation portion and the range total on hover', async () => {
    render(<CreditsCostCard cost={costWithWorkers} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('按数字员工');
    expect(breakdown).toHaveTextContent('DEV8含对话 2 credits');
    expect(breakdown).toHaveTextContent('CR4.5');
    expect(breakdown).not.toHaveTextContent('IDLE');
    expect(breakdown).toHaveTextContent('总计12.5');
  });

  it('hides the conversation portion when a worker has no conversation credits', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [{ agentId: 101, agentName: 'CR', credits: 4.5, conversationCredits: 0 }] }} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('CR4.5');
    expect(breakdown).not.toHaveTextContent('含对话');
  });

  it('does not attach hover detail for legacy payloads without byWorker', async () => {
    render(<CreditsCostCard cost={cost} />);

    fireEvent.mouseEnter(screen.getByText('总 Credits'));
    await waitPastHoverDelay();

    expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('does not attach hover detail when every worker row is non-positive', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [{ agentId: 102, agentName: 'IDLE', credits: 0 }] }} />);

    fireEvent.mouseEnter(screen.getByText('总 Credits'));
    await waitPastHoverDelay();

    expect(screen.queryByTestId('insights-credits-breakdown')).not.toBeInTheDocument();
  });

  it('falls back to the agent id and then to an unknown worker label', async () => {
    render(<CreditsCostCard cost={{ ...cost, byWorker: [
      { agentId: 100, agentName: null, credits: 1 },
      { agentId: null, agentName: '   ', credits: 1 },
    ] }} />);

    const breakdown = await openBreakdown();

    expect(breakdown).toHaveTextContent('agent-100');
    expect(breakdown).toHaveTextContent('未知数字员工');
  });

  it('keeps the breakdown while switching languages', async () => {
    render(<CreditsCostCard cost={costWithWorkers} variant="panel" />);
    const breakdown = await openBreakdown();

    await act(async () => { await i18n.changeLanguage('en-US'); });

    expect(screen.getByTestId('insights-credits-breakdown')).toBe(breakdown);
    expect(breakdown).toHaveTextContent('By digital worker');
    expect(breakdown).toHaveTextContent('Includes 2 credits from conversations');
    expect(breakdown).toHaveTextContent('Total12.5');
  });
});
