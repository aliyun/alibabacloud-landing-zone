import { afterEach, describe, it, expect } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkitemTimeline } from './WorkitemTimeline';
import { i18n } from '@/shared/i18n';
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

afterEach(async () => { cleanup(); await i18n.changeLanguage('zh-CN'); });

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

it.each(['zh-CN', 'en-US'])('keeps HTML-like actor and assignee names literal in %s for every known event', async (locale) => {
  await i18n.changeLanguage(locale);
  const actor = 'Alice <strong>name</strong>';
  const assignee = 'Bob <strong>team</strong> & friends';
  const knownEvents = ['CREATE', 'EDIT', 'STATUS_CHANGE', 'ASSIGN', 'DISPATCH', 'RESULT', 'COMMENT'];
  const { container } = renderTimeline({ events: knownEvents.map((eventType, index) => ({
    ...events[0], id: index + 1, eventType, actorDisplayName: actor,
    fromVal: null, toVal: eventType === 'ASSIGN' ? 'assignee-id' : null,
    toValDisplay: eventType === 'ASSIGN' ? assignee : null,
  })) });
  expect(screen.getAllByText(actor)).toHaveLength(knownEvents.length);
  expect(screen.getByText(assignee)).toBeInTheDocument();
  expect(container.querySelectorAll('strong')).toHaveLength(knownEvents.length);
});

it.each(['zh-CN', 'en-US'])('keeps owned timeline decorative icons out of accessible names in %s', async (locale) => {
  await i18n.changeLanguage(locale);
  renderTimeline();
  expect(screen.queryAllByRole('img')).toHaveLength(0);
});

it('renders held source identities across locales while malformed metadata and authored names remain raw', async () => {
  const raw = 'Aone 用户（staffId: <b>42</b>）';
  const fixture = [
    { ...events[0], id: 20, actorDisplayName: raw, actorNameFallback: { provider: 'AONE', subjectId: '<b>42</b>', displayName: null } },
    { ...events[0], id: 21, actorDisplayName: raw },
    { ...events[0], id: 22, actorDisplayName: raw, actorNameFallback: { provider: 'AONE', subjectId: '<b>42</b>', displayName: raw } },
    { ...events[0], id: 23, actorDisplayName: raw, actorNameFallback: { provider: ' ', subjectId: '<b>42</b>', displayName: null } },
  ];
  const { container } = renderTimeline({ events: fixture });
  expect(screen.getAllByText(raw)).toHaveLength(4);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('Aone user (staffId: <b>42</b>)')).toBeInTheDocument();
  expect(screen.getAllByText(raw)).toHaveLength(3);
  expect(container.querySelector('b')).toBeNull();
  expect(fixture[0].actorDisplayName).toBe(raw);
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getAllByText(raw)).toHaveLength(4);
});
