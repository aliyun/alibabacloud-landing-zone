import { describe, it, expect } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useDeliveryProgress } from '../hooks';
import { i18n } from '@/shared/i18n';
import { WorkitemCreditsBadge } from './WorkitemCreditsBadge';
import type { WorkitemUsageSummary } from '@/shared/types/workitem';

const usage: WorkitemUsageSummary = {
  credits: 70,
  runs: [
    { agentId: 40, agentName: 'DEV', runIndex: 1, label: 'DEV run-1', credits: 20 },
    { agentId: 41, agentName: 'CR', runIndex: 1, label: 'CR run-1', credits: 20 },
    { agentId: 40, agentName: 'DEV', runIndex: 2, label: 'DEV run-2', credits: 30 },
  ],
};

async function openBreakdown() {
  fireEvent.mouseEnter(screen.getByTestId('workitem-credits-badge'));
  return screen.findByTestId('workitem-credits-breakdown');
}

describe('WorkitemCreditsBadge', () => {
  it('renders the accumulated credits with two decimals next to a bolt icon', () => {
    render(<WorkitemCreditsBadge usage={usage} />);

    const badge = screen.getByTestId('workitem-credits-badge');
    expect(badge).toHaveTextContent('70.00 Credits');
    expect(badge.querySelector('.anticon-thunderbolt')).not.toBeNull();
  });

  it('keeps trailing zeros for whole-number credits', () => {
    render(<WorkitemCreditsBadge usage={{ credits: 20, runs: [] }} />);
    expect(screen.getByTestId('workitem-credits-badge')).toHaveTextContent('20.00 Credits');
  });

  it('lists every run and the total on hover', async () => {
    render(<WorkitemCreditsBadge usage={usage} />);

    const breakdown = await openBreakdown();

    expect(breakdown.textContent).toContain('DEV run-1: 20 credits');
    expect(breakdown.textContent).toContain('CR run-1: 20 credits');
    expect(breakdown.textContent).toContain('DEV run-2: 30 credits');
    expect(breakdown.textContent).toContain('总计: 70.00 Credits');
  });

  it('orders runs numerically across agents and keeps ties and unnumbered runs stable', async () => {
    const runs = [
      { agentId: 40, runIndex: 13, label: 'DEV run-13', credits: 1 },
      { agentId: 41, runIndex: 2, label: 'QA run-2', credits: 1 },
      { agentId: 40, runIndex: 1, label: 'DEV run-1', credits: 0 },
      { agentId: 40, runIndex: 2, label: 'DEV run-2', credits: 1 },
      { label: 'Legacy run', credits: 1 },
      { agentId: 41, runIndex: 1, label: 'QA run-1', credits: 1 },
      { label: 'Other legacy run', credits: 1 },
    ];
    const original = [...runs];
    render(<WorkitemCreditsBadge usage={{ credits: 6, runs }} />);
    const breakdown = await openBreakdown();
    expect(Array.from(breakdown.children).slice(0, -1).map(row => row.textContent)).toEqual([
      'QA run-1: 1 credits',
      'QA run-2: 1 credits',
      'DEV run-2: 1 credits',
      'DEV run-13: 1 credits',
      'Legacy run: 1 credits',
      'Other legacy run: 1 credits',
    ]);
    expect(runs).toEqual(original);
  });

  it('renders nothing before hover', () => {
    render(<WorkitemCreditsBadge usage={usage} />);
    expect(screen.queryByTestId('workitem-credits-breakdown')).not.toBeInTheDocument();
  });

  it('builds the run label from agent name and run index when the backend omits it', async () => {
    render(
      <WorkitemCreditsBadge
        usage={{ credits: 12.5, runs: [{ agentId: 40, agentName: 'DEV', runIndex: 2, credits: 12.5 }] }}
      />,
    );

    const breakdown = await openBreakdown();

    expect(breakdown.textContent).toContain('DEV 第 2 次执行: 12.5 credits');
    expect(breakdown.textContent).toContain('总计: 12.50 Credits');
  });

  it('falls back to the agent id, then to unknown, when no name is available', async () => {
    render(
      <WorkitemCreditsBadge
        usage={{
          credits: 5,
          runs: [
            { agentId: 77, runIndex: 1, credits: 3 },
            { credits: 2 },
          ],
        }}
      />,
    );

    const breakdown = await openBreakdown();

    expect(breakdown.textContent).toContain('agent-77 第 1 次执行: 3 credits');
    expect(breakdown.textContent).toContain('未知 第 2 次执行: 2 credits');
  });

  it('skips runs without positive credits but keeps the total', async () => {
    render(
      <WorkitemCreditsBadge
        usage={{
          credits: 8,
          runs: [
            { agentId: 40, agentName: 'DEV', runIndex: 1, label: 'DEV run-1', credits: 0 },
            { agentId: 40, agentName: 'DEV', runIndex: 2, label: 'DEV run-2', credits: null },
            { agentId: 40, agentName: 'DEV', runIndex: 3, label: 'DEV run-3', credits: 8 },
          ],
        }}
      />,
    );

    const breakdown = await openBreakdown();

    expect(breakdown.textContent).not.toContain('DEV run-1');
    expect(breakdown.textContent).not.toContain('DEV run-2');
    expect(breakdown.textContent).toContain('DEV run-3: 8 credits');
    expect(breakdown.textContent).toContain('总计: 8.00 Credits');
  });

  it('shows the total even when the run breakdown is missing', async () => {
    render(<WorkitemCreditsBadge usage={{ credits: 3.14159 }} />);

    const breakdown = await openBreakdown();

    expect(breakdown.textContent).toContain('总计: 3.14 Credits');
  });

  it('renders nothing when usage is missing, empty or zero', () => {
    expect(render(<WorkitemCreditsBadge />).container).toBeEmptyDOMElement();
    expect(render(<WorkitemCreditsBadge usage={null} />).container).toBeEmptyDOMElement();
    expect(render(<WorkitemCreditsBadge usage={{}} />).container).toBeEmptyDOMElement();
    expect(render(<WorkitemCreditsBadge usage={{ credits: 0, runs: usage.runs }} />).container)
      .toBeEmptyDOMElement();
    expect(render(<WorkitemCreditsBadge usage={{ credits: -5 }} />).container).toBeEmptyDOMElement();
  });
});


it('renders new owned run labels reactively while retaining raw and unknown labels in the same mounted popup', async () => {
 const runs = [
  {agentId: 40, agentName: '  <b>DEV</b>  ', runIndex: 2, label: '<b>DEV</b> run-2', labelKind: 'AGENT_RUN', credits: 1},
  {agentId: 77, agentName: '　', runIndex: 1, label: 'agent-77 run-1', labelKind: 'AGENT_RUN', credits: 1},
  {agentId: null, agentName: null, runIndex: 1, label: 'unknown run-1', labelKind: 'AGENT_RUN', credits: 1},
  {label: '<b>DEV</b> run-2', credits: 1},
  {label: 'unknown run-1', labelKind: 'FUTURE', credits: 1},
 ];
 const { container } = render(<WorkitemCreditsBadge usage={{ credits: 5, runs }} />);
 const badge = screen.getByTestId('workitem-credits-badge'); const popup = await openBreakdown();
 expect(popup).toHaveTextContent('<b>DEV</b> 第 2 次执行:');
 expect(popup).toHaveTextContent('agent-77 第 1 次执行:'); expect(popup).toHaveTextContent('未知 第 1 次执行:');
 expect(popup).toHaveTextContent('<b>DEV</b> run-2:'); expect(popup).toHaveTextContent('unknown run-1:');
 expect(popup.querySelector('b')).toBeNull(); expect(screen.queryByRole('img', { name: 'thunderbolt' })).not.toBeInTheDocument();
 expect(container.querySelector('.anticon-thunderbolt')).not.toBeNull();
 await act(async () => { await i18n.changeLanguage('en-US'); });
 expect(screen.getByTestId('workitem-credits-breakdown')).toBe(popup); expect(screen.getByTestId('workitem-credits-badge')).toBe(badge);
 expect(popup).toHaveTextContent('<b>DEV</b> run-2:'); expect(popup).toHaveTextContent('Unknown run-1:');
 await act(async () => { await i18n.changeLanguage('zh-CN'); });
 expect(popup).toHaveTextContent('未知 第 1 次执行:'); expect(runs[0].label).toBe('<b>DEV</b> run-2');
});

it.each(['zh-CN', 'en-US'])('keeps the installed bolt decorative in %s', async locale => {
 await i18n.changeLanguage(locale);
 const { container } = render(<WorkitemCreditsBadge usage={usage} />);
 expect(container.querySelector('.anticon-thunderbolt')).not.toBeNull();
 expect(screen.queryByRole('img', { name: 'thunderbolt' })).not.toBeInTheDocument();
 await act(async () => { await i18n.changeLanguage('zh-CN'); });
});


it('uses the actual cached delivery hook through zh-en-zh with no query, popup or row remount', async () => {
 let requests = 0;
 const raw = { credits: 2, runs: [
  { agentId: 40, agentName: '\u00a0', runIndex: 1, label: '\u00a0 run-1', labelKind: 'AGENT_RUN', credits: 1 },
  { agentId: 41, agentName: 'raw', runIndex: 1, label: 'unknown run-1', credits: 1 },
 ] };
 server.use(http.get('/api/workitems/100/delivery-progress', () => { requests++; return HttpResponse.json({ success: true, data: { agents: [], steps: [], totalUsage: raw } }); }));
 const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
 function CachedCredits() { const { data } = useDeliveryProgress('100'); return <WorkitemCreditsBadge usage={data?.totalUsage} />; }
 render(<QueryClientProvider client={client}><CachedCredits /></QueryClientProvider>);
 await screen.findByTestId('workitem-credits-badge'); const popup = await openBreakdown(); const row = popup.firstElementChild;
 expect(popup).toHaveTextContent('第 1 次执行:'); expect(popup).toHaveTextContent('unknown run-1:');
 const keys = client.getQueryCache().getAll().map(q => q.queryKey);
 for (const locale of ['en-US', 'zh-CN']) {
  await act(async () => { await i18n.changeLanguage(locale); });
  expect(screen.getByTestId('workitem-credits-breakdown')).toBe(popup); expect(popup.firstElementChild).toBe(row);
  expect(popup).toHaveTextContent(locale === 'en-US' ? 'run-1:' : '第 1 次执行:');
  expect(popup).not.toHaveTextContent('agent-40'); expect(popup).toHaveTextContent('unknown run-1:');
 }
 expect(requests).toBe(1); expect(client.getQueryCache().getAll().map(q => q.queryKey)).toEqual(keys);
 expect(raw.runs[0].agentName).toBe('\u00a0'); expect(raw.runs[0].label).toBe('\u00a0 run-1');
 client.clear();
});
