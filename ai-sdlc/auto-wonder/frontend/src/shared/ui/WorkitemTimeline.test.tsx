import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkitemTimeline } from './WorkitemTimeline';
import type { TimelineEvent } from '@/shared/types/workitem';

const events: TimelineEvent[] = [
  { id: 1, eventType: 'CREATE', fromVal: null, toVal: 'new', actorType: 'HUMAN', actorRef: 10, detailJson: null, gmtCreate: '2026-07-01T10:00:00Z' },
  { id: 2, eventType: 'STATUS_CHANGE', fromVal: 'new', toVal: 'in_progress', actorType: 'HUMAN', actorRef: 10, detailJson: null, gmtCreate: '2026-07-01T11:00:00Z' },
  { id: 3, eventType: 'COMMENT', fromVal: null, toVal: null, actorType: 'HUMAN', actorRef: 20, detailJson: null, gmtCreate: '2026-07-01T12:00:00Z' },
];

function renderTimeline(props = {}) {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemTimeline events={events} {...props} />
    </QueryClientProvider>,
  );
}

describe('WorkitemTimeline', () => {
  it('renders timeline events', () => {
    renderTimeline();
    expect(screen.getByText(/创建了工单/)).toBeInTheDocument();
    expect(screen.getByText(/in_progress/)).toBeInTheDocument();
  });

  it('renders status change with from/to', () => {
    renderTimeline();
    expect(screen.getByText('new')).toBeInTheDocument();
  });
});
