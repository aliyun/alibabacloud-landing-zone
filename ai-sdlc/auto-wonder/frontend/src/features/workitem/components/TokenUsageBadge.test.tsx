import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { TokenUsageBadge, StepTokenBadge } from './TokenUsageBadge';
import type { UsageSummary } from '@/shared/types/workitem';

describe('TokenUsageBadge', () => {
  const usage: UsageSummary = {
    model: 'auto',
    inputTokens: 700_000,
    outputTokens: 300_000,
    cacheReadTokens: 100_000,
    reasoningTokens: 0,
    credits: 82.61,
  };

  it('renders compact total tokens instead of credits and model tag', () => {
    render(<TokenUsageBadge usage={usage} />);
    expect(screen.getByText('1M')).toBeInTheDocument();
    expect(screen.queryByText(/💰/)).not.toBeInTheDocument();
    expect(screen.queryByText(/82\.61/)).not.toBeInTheDocument();
    expect(screen.queryByText('auto')).not.toBeInTheDocument();
  });

  it('shows usage details including credits on hover', async () => {
    render(<TokenUsageBadge usage={usage} />);
    fireEvent.mouseEnter(screen.getByText('1M'));
    expect(await screen.findByText('Total tokens:')).toBeInTheDocument();
    expect(screen.getByText('Input tokens:')).toBeInTheDocument();
    expect(screen.getByText('700,000')).toBeInTheDocument();
    expect(screen.getByText('300,000')).toBeInTheDocument();
    expect(screen.getByText(/模型:/)).toBeInTheDocument();
    expect(screen.getByText(/Credits:/)).toBeInTheDocument();
    expect(screen.getByText('82.61')).toBeInTheDocument();
  });

  it('renders nothing when there is no token usage', () => {
    const { container } = render(<TokenUsageBadge usage={{ credits: 12 }} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('StepTokenBadge', () => {
  it('shows total tokens instead of credits', () => {
    render(<StepTokenBadge usage={{ inputTokens: 30_000, outputTokens: 13_000, credits: 43 }} />);
    expect(screen.getByText('43K')).toBeInTheDocument();
    expect(screen.queryByText('43')).not.toBeInTheDocument();
  });

  it('renders nothing when there is no token usage', () => {
    const { container } = render(<StepTokenBadge usage={{ credits: 43 }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows readable light text inside the dark hover tooltip', async () => {
    render(<StepTokenBadge usage={{ inputTokens: 30_000, outputTokens: 13_000, credits: 43 }} />);
    fireEvent.mouseEnter(screen.getByText('43K'));
    const label = await screen.findByText('Input tokens:');
    expect(label).toHaveStyle({ color: 'rgba(255, 255, 255, 0.85)' });
    expect(screen.getByText(/Credits:/)).toBeInTheDocument();
    expect(screen.getByText('43')).toBeInTheDocument();
  });
});
