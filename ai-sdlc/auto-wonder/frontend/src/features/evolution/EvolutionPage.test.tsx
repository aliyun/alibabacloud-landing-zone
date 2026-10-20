import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { EvolutionPage } from './EvolutionPage';
import type { EvolutionAdminOverview } from './api';
import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { createAppRoutes } from '@/app/router';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return { queryClient, ...render(
    <QueryClientProvider client={queryClient}>
      <EvolutionPage />
    </QueryClientProvider>,
  ) };
}

function overview(): EvolutionAdminOverview {
  return {
    proposals: [
      {
        id: 100,
        assetType: 'MEMORY',
        assetId: null,
        triggerType: 'WORKER_DELTA',
        status: 'REPLAY_PASSED',
        rootEvidenceJson: '[{"sourceType":"WORKER_DELTA","sourceRef":"trace:1"}]',
        policyJson: '{"action":"PATCH","reasonCode":"repeated_context_failure"}',
        candidatePatchJson: '{"title":"Use pnpm","contentMd":"Use pnpm in this repo."}',
        replayJson: '{"verdict":"PASS"}',
        gmtCreate: '2026-07-24T10:00:00Z',
      },
      {
        id: 101,
        assetType: 'SKILL',
        assetId: 88,
        triggerType: 'WORKER_DELTA',
        status: 'TRIAL',
        rootEvidenceJson: '[{"sourceType":"WORKER_DELTA","sourceRef":"trace:2"}]',
        policyJson: '{"action":"PATCH","reasonCode":"candidate_trial"}',
        candidatePatchJson: '{"name":"repo-checkout-safety","description":"Load repo map before checkout."}',
        trialJson: '{"taskPatternKey":"repo-checkout","decision":"CONTINUE_TRIAL","baselineSnapshot":{"posteriorMean":0.4,"effectiveSampleSize":8},"candidatePosteriorMean":0.67,"candidateEffectiveSampleSize":3,"posteriorWinProbability":0.82,"posteriorLoseProbability":0.04,"expectedLift":0.27}',
        gmtCreate: '2026-07-24T10:02:00Z',
      },
    ],
    evidence: [
      {
        id: 201,
        assetType: 'SKILL',
        assetId: 88,
        posteriorType: 'UPLIFT',
        contextKey: 'skill:review',
        sourceType: 'REPLAY_RESULT',
        sourceRef: 'proposal:100:replay',
        outcome: 'POSITIVE',
        posteriorMean: 0.67,
        effectiveSampleSize: 3,
        gmtCreate: '2026-07-24T10:01:00Z',
      },
    ],
  };
}

function serveOverview(data = overview()) {
  server.use(
    http.get('/api/evolution/admin/overview', () => HttpResponse.json({ success: true, data })),
    http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({ success: true, data: {
      cards: [{ assetType: 'SKILL', assetId: 88, name: '<b>自定义技能</b>', category: 'CODING',
        triggerHint: '<i>原始提示</i>', lazyLoadRef: '/api/skills/88', posteriorMean: 0.82, effectiveSampleSize: 12 }],
    } })),
  );
}

function deferredResponse() {
  let finish!: (response: HttpResponse) => void;
  const promise = new Promise<HttpResponse>(resolve => { finish = resolve; });
  return { promise, finish };
}

async function locale(value: 'zh-CN' | 'en-US') {
  await act(async () => { await i18n.changeLanguage(value); });
}

describe('EvolutionPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('zh-CN');
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  // Breaks if the cached route/column memo keeps old labels, remounts drafts, or interprets authored markup.
  it('switches the actual cached evolution route and all three tables while retaining the JSON draft', async () => {
    const data = overview();
    data.proposals[0].candidatePatchJson = '{"title":"<b>用户候选</b>"}';
    serveOverview(data);
    const route = createAppRoutes().flatMap(r => r.children ?? []).find(r => r.path === '/evolution');
    expect(route).toBeDefined();
    const router = createMemoryRouter([route!], { initialEntries: ['/evolution'] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    const user = userEvent.setup();
    const heading = await screen.findByRole('heading', { name: '自进化' });
    await screen.findByText('<b>用户候选</b>');
    await act(async () => { await user.click(screen.getByRole('button', { name: /提交 Delta/ })); });
    const dialog = screen.getByRole('dialog');
    const input = within(dialog).getByLabelText('Delta JSON');
    const defaults = (input as HTMLTextAreaElement).value;
    expect(JSON.parse(defaults)).toMatchObject({ evidenceEvent: { rawOutcome: 'NEGATIVE', sourceType: 'WORKER_DELTA' },
      draftDeltaJson: { patch: { title: 'Worker delta title', contentMd: 'Worker produced delta content.' } } });
    fireEvent.change(input, { target: { value: '{ "用户草稿": "<i>raw</i>" }' } });
    await locale('en-US');
    expect(screen.getByRole('heading', { name: 'Self-evolution' })).toBe(heading);
    expect(screen.getByRole('dialog', { name: 'Submit Worker Delta' })).toBe(dialog);
    expect(within(dialog).getByLabelText('Delta JSON')).toBe(input);
    expect(input).toHaveValue('{ "用户草稿": "<i>raw</i>" }');
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    await act(async () => { await user.click(within(dialog).getByRole('button', { name: 'Cancel' })); });
    const table = screen.getByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'Candidate summary' })).toBeInTheDocument();
    expect(within(table).getAllByText('Trial')).not.toHaveLength(0);
    expect(within(table).getByText('Replay passed')).toBeInTheDocument();
    for (const name of ['Release Agent', 'Trial pass', 'Trial fail', 'Decide']) expect(within(table).getByRole('button', { name })).toBeInTheDocument();
    expect(within(table).getByText('Continue trial')).toBeInTheDocument();
    expect(within(table).getByText('Baseline 0.40 / candidate 0.67 · N=3')).toBeInTheDocument();
    expect(within(table).getByText('<b>用户候选</b>').querySelector('b')).toBeNull();
    expect(within(table).getByText('repeated_context_failure')).toBeInTheDocument();
    expect(within(table).getAllByText('WORKER_DELTA')).toHaveLength(2);
    expect(within(table).getByText(formatDateTime('2026-07-24T10:00:00Z', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    }))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' }).querySelector('[role="img"]')).toHaveAttribute('aria-hidden', 'true');
    await act(async () => { await user.click(screen.getByRole('tab', { name: /Audit evidence \(1\)/ })); });
    expect(screen.getByRole('columnheader', { name: 'Source reference' })).toBeInTheDocument();
    expect(screen.getByText('Positive')).toBeInTheDocument();
    expect(screen.getByText('proposal:100:replay')).toBeInTheDocument();
    await act(async () => { await user.click(screen.getByRole('tab', { name: /Asset manifest \(1\)/ })); });
    expect(screen.getByRole('columnheader', { name: 'Trigger hint' })).toBeInTheDocument();
    expect(screen.getByText('<b>自定义技能</b>').querySelector('b')).toBeNull();
    expect(screen.getByText('<i>原始提示</i>').querySelector('i')).toBeNull();
    expect(screen.getByText('CODING')).toBeInTheDocument();
    await locale('zh-CN');
    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('资产清单 (1)');
    expect(screen.getByRole('heading', { name: '自进化' })).toBe(heading);
  });

  // Each form must refresh only its existing errors and preserve the other form's pristine default/draft.
  it.each(['delta', 'replay'])('localizes the independent %s form errors without changing its draft or pristine peer', async kind => {
    const data = overview();
    data.proposals[0].status = 'VALIDATED';
    serveOverview(data);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Use pnpm');
    await act(async () => { await user.click(screen.getByRole('button', { name: kind === 'delta' ? /提交 Delta/ : /^(?:回\s*放|Replay)$/ })); });
    const dialog = screen.getByRole('dialog');
    const input = within(dialog).getByLabelText(kind === 'delta' ? 'Delta JSON' : 'Replay JSON');
    const defaults = (input as HTMLTextAreaElement).value;
    fireEvent.change(input, { target: { value: '' } });
    await act(async () => { await user.click(within(dialog).getByRole('button', { name: kind === 'delta' ? /^提\s*交$/ : /^记\s*录$/ })); });
    expect(await within(dialog).findByText(kind === 'delta' ? /请输入 [dD]elta JSON/ : /请输入 [rR]eplay JSON/)).toBeInTheDocument();
    await locale('en-US');
    expect(await within(dialog).findByText(kind === 'delta' ? 'Enter Delta JSON' : 'Enter Replay JSON')).toBeInTheDocument();
    expect(within(dialog).getByLabelText(kind === 'delta' ? 'Delta JSON' : 'Replay JSON')).toBe(input);
    expect(input).toHaveValue('');
    const draft = '{ "checks": [ { "name": "用户检查", "status": "PASS" } ] }';
    fireEvent.change(input, { target: { value: draft } });
    await waitFor(() => expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument());
    await locale('zh-CN');
    expect(input).toHaveValue(draft);
    await act(async () => { await user.click(within(dialog).getByRole('button', { name: /^取\s*消$/ })); });
    await act(async () => { await user.click(screen.getByRole('button', { name: kind === 'delta' ? /^(?:回\s*放|Replay)$/ : /提交 Delta/ })); });
    const peer = screen.getByRole('dialog');
    const peerInput = within(peer).getByLabelText(kind === 'delta' ? 'Replay JSON' : 'Delta JSON');
    const peerDefaults = (peerInput as HTMLTextAreaElement).value;
    await locale('en-US');
    expect(peerInput).toHaveValue(peerDefaults);
    expect(within(peer).queryByRole('alert')).not.toBeInTheDocument();
    expect(JSON.parse(kind === 'delta' ? peerDefaults : defaults)).toEqual({ checks: [{ name: 'manual-check', status: 'PASS' }] });
  });

  it('uses current column access-command labels for read-only actions and localized confirms', async () => {
    const data = overview();
    data.proposals[0].status = 'RELEASED';
    data.proposals[1].status = 'PROPOSED';
    serveOverview(data);
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Use pnpm');
    await locale('en-US');
    await act(async () => { await user.click(screen.getByRole('button', { name: 'Validate' })); });
    expect(error).toHaveBeenLastCalledWith('Current access: Read-only access. Validate evolution candidate requires Read and write access.');
    await act(async () => { await user.click(screen.getByRole('button', { name: 'Rollback' })); });
    expect(await screen.findByText('Roll back this release?')).toBeInTheDocument();
    await act(async () => { await user.click(screen.getByRole('button', { name: 'Confirm' })); });
    expect(error).toHaveBeenLastCalledWith('Current access: Read-only access. Roll back evolution release requires Read and write access.');
    await act(async () => { await user.click(screen.getByRole('button', { name: 'Reject' })); });
    expect(await screen.findByText('Reject this candidate?')).toBeInTheDocument();
    await act(async () => { await user.click(screen.getByRole('button', { name: 'Confirm' })); });
    expect(error).toHaveBeenLastCalledWith('Current access: Read-only access. Reject evolution candidate requires Read and write access.');
  });

  it.each(['action', 'replay', 'delta'] as const)('uses completion locale for the %s mutation and keeps canonical payloads', async kind => {
    const data = overview();
    data.proposals[0].status = kind === 'replay' ? 'VALIDATED' : 'PROPOSED';
    serveOverview(data);
    const pending = deferredResponse();
    let body: unknown;
    let received = false;
    const url = kind === 'action' ? '/api/evolution/proposals/100/reject' : kind === 'replay' ? '/api/evolution/replay/execute' : '/api/evolution/orchestrate';
    server.use(http.post(url, async ({ request }) => { body = await request.json(); received = true; return pending.promise; }));
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Use pnpm');
    if (kind === 'action') {
      await act(async () => { await user.click(screen.getAllByRole('button', { name: /^(?:拒\s*绝|Reject)$/ })[0]); });
      await act(async () => { await user.click(screen.getByRole('button', { name: /^(?:确\s*认|确\s*定|OK)$/ })); });
    } else {
      await act(async () => { await user.click(screen.getByRole('button', { name: kind === 'delta' ? /提交 Delta/ : /^(?:回\s*放|Replay)$/ })); });
      const dialog = screen.getByRole('dialog');
      if (kind === 'replay') fireEvent.change(within(dialog).getByLabelText('Replay JSON'), { target: { value: '{ "checks": [{ "name": "用户检查", "status": "PASS" }] }' } });
      await act(async () => { await user.click(within(dialog).getByRole('button', { name: kind === 'delta' ? /^提\s*交$/ : /^记\s*录$/ })); });
    }
    await waitFor(() => expect(received).toBe(true));
    await locale('en-US');
    expect(success).not.toHaveBeenCalled();
    await act(async () => { pending.finish(HttpResponse.json({ success: true, data: { proposalId: 102, action: 'PROPOSAL_CREATED' } })); });
    await waitFor(() => expect(success).toHaveBeenCalledWith(kind === 'action' ? 'Action submitted' : kind === 'replay' ? 'Replay recorded' : 'Entered trial #102'));
    if (kind === 'action') expect(body).toEqual({ reason: 'Rejected from evolution console' });
    if (kind === 'replay') expect(body).toEqual({ proposalId: 100, autoValidate: false, replaySuiteJson: '{ "checks": [{ "name": "用户检查", "status": "PASS" }] }' });
    if (kind === 'delta') expect(body).toMatchObject({ evidenceEvent: { rawOutcome: 'NEGATIVE', contextKey: 'repo:auto-wonder', idempotencyKey: 'trace:replace-me:memory' }, draftDeltaJson: { patch: { contentMd: 'Worker produced delta content.' } } });
  });

  it.each(['action', 'replay', 'delta'] as const)('preserves backend-specific error details after the pending %s mutation switches locale', async kind => {
    const data = overview();
    if (kind === 'replay') data.proposals[0].status = 'VALIDATED';
    serveOverview(data);
    const pending = deferredResponse();
    let received = false;
    const url = kind === 'action' ? '/api/evolution/proposals/101/trial/decide' : kind === 'replay' ? '/api/evolution/replay/execute' : '/api/evolution/orchestrate';
    server.use(http.post(url, () => { received = true; return pending.promise; }));
    const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    renderPage();
    await screen.findByText('Use pnpm');
    const user = userEvent.setup();
    if (kind === 'action') await act(async () => { await user.click(screen.getByRole('button', { name: /^决\s*策$/ })); });
    else {
      await act(async () => { await user.click(screen.getByRole('button', { name: kind === 'delta' ? /提交 Delta/ : /^(?:回\s*放|Replay)$/ })); });
      await act(async () => { await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: kind === 'delta' ? /^提\s*交$/ : /^记\s*录$/ })); });
    }
    await waitFor(() => expect(received).toBe(true));
    await locale('en-US');
    await act(async () => { pending.finish(HttpResponse.json({ success: false, code: 'SPECIFIC_EVOLUTION_DETAIL', message: '<b>服务端原始原因</b>' })); });
    await waitFor(() => expect(error).toHaveBeenCalledWith('<b>服务端原始原因</b>'));
  });

  it.each(['delta', 'replay'] as const)('keeps invalid %s JSON intact with the current localized parsing message', async kind => {
    const data = overview();
    data.proposals[0].status = 'VALIDATED';
    serveOverview(data);
    const error = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    renderPage();
    await screen.findByText('Use pnpm');
    const user = userEvent.setup();
    await act(async () => { await user.click(screen.getByRole('button', { name: kind === 'delta' ? /提交 Delta/ : /^(?:回\s*放|Replay)$/ })); });
    const dialog = screen.getByRole('dialog');
    const input = within(dialog).getByLabelText(kind === 'delta' ? 'Delta JSON' : 'Replay JSON');
    fireEvent.change(input, { target: { value: '{ invalid JSON 用户输入' } });
    await locale('en-US');
    await act(async () => { await user.click(within(dialog).getByRole('button', { name: kind === 'delta' ? 'Submit' : 'Record' })); });
    await waitFor(() => expect(error).toHaveBeenLastCalledWith(kind === 'delta' ? 'Delta JSON is not valid JSON' : 'Replay JSON is not valid JSON'));
    expect(input).toHaveValue('{ invalid JSON 用户输入');
    await locale('zh-CN');
    await act(async () => { await user.click(within(dialog).getByRole('button', { name: kind === 'delta' ? /^提\s*交$/ : /^记\s*录$/ })); });
    await waitFor(() => expect(error).toHaveBeenLastCalledWith(kind === 'delta' ? 'Delta JSON 不是合法 JSON' : 'Replay JSON 不是合法 JSON'));
  });

  it.each(['CUSTOM_ACTION', undefined])('keeps the no-proposal Delta result action %s raw and translates its completion sentence', async action => {
    serveOverview();
    const pending = deferredResponse();
    let received = false;
    server.use(http.post('/api/evolution/orchestrate', () => { received = true; return pending.promise; }));
    const success = vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Use pnpm');
    await act(async () => { await user.click(screen.getByRole('button', { name: /提交 Delta/ })); });
    await act(async () => { await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^提\s*交$/ })); });
    await waitFor(() => expect(received).toBe(true));
    await locale('en-US');
    await act(async () => { pending.finish(HttpResponse.json({ success: true, data: { action } })); });
    await waitFor(() => expect(success).toHaveBeenCalledWith(`Submitted: ${action || 'NO_TRIGGER'}`));
  });

  it('resolves plural tab labels with numeric zero while both queries are unavailable', async () => {
    const pending = deferredResponse();
    const manifestPending = deferredResponse();
    server.use(
      http.get('/api/evolution/admin/overview', () => pending.promise),
      http.get('/api/evolution/admin/asset-manifest', () => manifestPending.promise),
    );
    const { queryClient } = renderPage();
    expect(screen.getByRole('tab', { name: /候选收件箱 \(0\)/ })).toBeInTheDocument();
    await locale('en-US');
    for (const name of [/Proposal inbox \(0\)/, /Audit evidence \(0\)/, /Asset manifest \(0\)/]) expect(screen.getByRole('tab', { name })).toBeInTheDocument();
    await act(async () => { pending.finish(HttpResponse.json({ success: false, code: 'EVOLUTION_UNAVAILABLE', message: 'raw reason' }));
      manifestPending.finish(HttpResponse.json({ success: false, code: 'EVOLUTION_UNAVAILABLE', message: 'raw reason' })); });
    await waitFor(() => {
      expect(queryClient.getQueryState(['evolution-overview'])?.status).toBe('error');
      expect(queryClient.getQueryState(['evolution-asset-manifest'])?.status).toBe('error');
    });
    for (const name of [/Proposal inbox \(0\)/, /Audit evidence \(0\)/, /Asset manifest \(0\)/]) expect(screen.getByRole('tab', { name })).toBeInTheDocument();
  });

  it('renders unknown and prototype-like status/asset/decision/verdict codes as safe raw text', async () => {
    const data = overview();
    data.proposals = ['constructor', '__proto__', 'toString', '<b>未知状态</b>'].map((code, index) => ({ ...data.proposals[1], id: 300 + index,
      assetType: code, status: code, trialJson: JSON.stringify({ decision: code }), candidatePatchJson: '{}' }));
    data.evidence = [{ ...data.evidence[0], outcome: 'constructor' }];
    serveOverview(data);
    renderPage();
    await screen.findAllByText('constructor');
    await locale('en-US');
    for (const code of ['constructor', '__proto__', 'toString', '<b>未知状态</b>']) {
      for (const node of screen.getAllByText(code)) { expect(node.querySelector('b')).toBeNull(); expect(node).toHaveClass('ant-tag-default'); }
    }
    await act(async () => { await userEvent.click(screen.getByRole('tab', { name: /Audit evidence \(1\)/ })); });
    expect(within(screen.getByRole('tabpanel', { name: /Audit evidence \(1\)/ })).getByText('constructor')).toHaveClass('ant-tag-default');
  });

  it('does not treat another field\'s finite code as a known status, asset, decision or outcome', async () => {
    const data = overview();
    data.proposals = [{ ...data.proposals[1], assetType: 'PASS', status: 'SKILL', trialJson: '{"decision":"PROPOSED"}' }];
    data.evidence = [{ ...data.evidence[0], outcome: 'RELEASED' }];
    serveOverview(data);
    renderPage();
    await screen.findByText('repo-checkout-safety');
    await locale('en-US');
    for (const code of ['PASS', 'SKILL', 'PROPOSED']) expect(screen.getByText(code)).toBeInTheDocument();
    await act(async () => { await userEvent.click(screen.getByRole('tab', { name: /Audit evidence \(1\)/ })); });
    expect(screen.getByText('RELEASED')).toBeInTheDocument();
  });

  it('renders lean evolution overview and agent releases replay-passed proposal', async () => {
    let releaseBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          contextKey: 'multi_repo_refactor',
          limit: 20,
          cards: [
            {
              assetType: 'SKILL',
              assetId: 88,
              name: 'multi-repo-refactor-safety',
              category: 'CODING',
              triggerHint: 'load repo-map before editing',
              lazyLoadRef: '/api/skills/88',
              posteriorMean: 0.82,
              effectiveSampleSize: 12,
            },
          ],
        },
      })),
      http.post('/api/evolution/proposals/100/agent-release', async ({ request }) => {
        releaseBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { proposalId: 100, action: 'RELEASED', status: 'RELEASED' },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('自进化')).toBeInTheDocument();
    expect(await screen.findByText('Use pnpm')).toBeInTheDocument();
    expect(screen.getByText('记忆')).toBeInTheDocument();
    expect(screen.getByText('回放通过')).toBeInTheDocument();
    expect(screen.getAllByText('PATCH').length).toBeGreaterThan(0);
    expect(screen.getByText('候选收件箱 (2)')).toBeInTheDocument();
    expect(screen.getByText('审计证据 (1)')).toBeInTheDocument();
    expect(screen.getByText('资产清单 (1)')).toBeInTheDocument();
    await act(async () => { await user.click(screen.getByText('资产清单 (1)')); });
    expect(await screen.findByText('multi-repo-refactor-safety')).toBeInTheDocument();
    expect(screen.getByText('/api/skills/88')).toBeInTheDocument();
    expect(screen.queryByText(/Gates/)).not.toBeInTheDocument();

    await act(async () => { await user.click(screen.getByText('候选收件箱 (2)')); });
    await act(async () => { await user.click(screen.getByRole('button', { name: /发布数字员工/ })); });

    await waitFor(() => {
      expect(releaseBody).toMatchObject({ allowRelease: true });
    });
  });

  it('shows trial posterior and records trial evidence before deciding', async () => {
    let trialEvidenceBody: Record<string, unknown> | null = null;
    let decideCalled = false;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
      http.post('/api/evolution/proposals/101/trial/evidence', async ({ request }) => {
        trialEvidenceBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            proposalId: 101,
            decision: 'CONTINUE_TRIAL',
            proposalStatus: 'TRIAL',
            taskPatternKey: 'repo-checkout',
            candidatePosteriorMean: 0.71,
            candidateEffectiveSampleSize: 4,
          },
        });
      }),
      http.post('/api/evolution/proposals/101/trial/decide', () => {
        decideCalled = true;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            proposalId: 101,
            decision: 'ADOPT',
            proposalStatus: 'APPROVED',
            taskPatternKey: 'repo-checkout',
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('repo-checkout-safety')).toBeInTheDocument();
    expect(screen.getByText('试验中')).toBeInTheDocument();
    expect(screen.getByText('repo-checkout')).toBeInTheDocument();
    expect(screen.getByText('继续试验')).toBeInTheDocument();
    expect(screen.getByText('基线 0.40 / 候选 0.67 · N=3')).toBeInTheDocument();
    expect(screen.getByText('胜出概率 0.82 / 落败概率 0.04 · 提升 0.27')).toBeInTheDocument();

    await act(async () => { await user.click(screen.getByRole('button', { name: /试验通过/ })); });
    await waitFor(() => {
      expect(trialEvidenceBody).toMatchObject({ rawOutcome: 'PASS', sourceType: 'HUMAN_REVIEW' });
    });

    await act(async () => { await user.click(screen.getByRole('button', { name: /^决\s*策$/ })); });
    await waitFor(() => {
      expect(decideCalled).toBe(true);
    });
  });

  it('submits worker delta JSON through orchestrate endpoint', async () => {
    let orchestrateBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
      http.post('/api/evolution/orchestrate', async ({ request }) => {
        orchestrateBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { action: 'PROPOSAL_CREATED', proposalId: 101, replayVerdict: 'PASS' },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Use pnpm');

    await act(async () => { await user.click(screen.getByRole('button', { name: /提交 Delta/ })); });
    await act(async () => { await user.clear(screen.getByLabelText('Delta JSON')); });
    fireEvent.change(screen.getByLabelText('Delta JSON'), { target: { value: JSON.stringify({
      evidenceEvent: {
        assetType: 'MEMORY',
        assetId: 0,
        posteriorType: 'UPLIFT',
        contextKey: 'repo:auto-wonder',
        sourceType: 'WORKER_DELTA',
        sourceRef: 'trace:front',
        outcome: 'NEGATIVE',
      },
      candidateAssetType: 'MEMORY',
      draftDeltaJson: { patch: { title: 'Worker delta', contentMd: 'Delta content' } },
    }) } });
    await act(async () => { await user.click(screen.getByRole('button', { name: /^提\s*交$/ })); });

    await waitFor(() => {
      expect(orchestrateBody).toMatchObject({ candidateAssetType: 'MEMORY' });
    });
  });

  it('keeps delta submission visible but does not open it for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
    );
    renderPage();
    await screen.findByText('Use pnpm');

    await act(async () => { await userEvent.click(screen.getByRole('button', { name: /提交 Delta/ })); });

    expect(error).toHaveBeenCalledWith('当前为只读权限，提交进化 Delta需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
