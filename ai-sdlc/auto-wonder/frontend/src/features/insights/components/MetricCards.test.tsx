import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MetricCards } from './MetricCards';
import type { InsightMetrics } from '../types';

const metrics: InsightMetrics = {
  cost: {
    totalTokens: 100000,
    avgTokensPerTask: 5000,
    dailyAvg: 14000,
    trend: [10, 12, 11, 13, 14, 12, 15],
    totalCredits: 82.61,
    avgCreditsPerTask: 12.5,
    dailyAvgCredits: 2.75,
    creditsTrend: [1.2, 2.5, 3.1, 4, 5.5, 6.2, 7.1],
  },
  efficiency: { completionRate: 80, totalTasks: 20, completedTasks: 16, avgDurationMinutes: 30, trend: [70, 72, 75, 77, 78, 79, 80] },
  stability: { successRate: 90, retryCount: 2, blockedCount: 1, trend: [88, 89, 90, 91, 90, 89, 90] },
  security: { highRiskOps: 1, complianceRate: 98, auditBlocks: 3, trend: [2, 1, 2, 1, 2, 2, 1] },
};

describe('MetricCards', () => {
  it('shows credits as the cost card main KPI', () => {
    render(<MetricCards metrics={metrics} />);

    expect(screen.getByText('总 Credits')).toBeInTheDocument();
    expect(screen.getByText('82.61')).toBeInTheDocument();
    expect(screen.getByText('12.5')).toBeInTheDocument();
    expect(screen.getByText('2.75')).toBeInTheDocument();
  });

  it('keeps the three token metrics out of the card face until hover', async () => {
    const user = userEvent.setup();
    render(<MetricCards metrics={metrics} />);

    expect(screen.queryByText('总 Token:')).not.toBeInTheDocument();
    expect(screen.queryByText('100K')).not.toBeInTheDocument();

    await user.hover(screen.getByText('总 Credits'));

    expect(await screen.findByText('总 Token:')).toBeInTheDocument();
    expect(screen.getByText('100K')).toBeInTheDocument();
    expect(screen.getByText('5.0K')).toBeInTheDocument();
    expect(screen.getByText('14K')).toBeInTheDocument();
  });

  it('renders zero credits without NaN when there is no usage data', () => {
    const empty: InsightMetrics = {
      ...metrics,
      cost: {
        totalTokens: 0,
        avgTokensPerTask: 0,
        dailyAvg: 0,
        trend: [0, 0, 0, 0, 0, 0, 0],
        totalCredits: 0,
        avgCreditsPerTask: 0,
        dailyAvgCredits: 0,
        creditsTrend: [0, 0, 0, 0, 0, 0, 0],
      },
    };
    render(<MetricCards metrics={empty} />);

    expect(screen.getAllByText('0')).toHaveLength(3);
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });
});
