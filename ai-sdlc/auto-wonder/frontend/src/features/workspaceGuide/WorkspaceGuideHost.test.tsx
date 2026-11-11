import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { WorkspaceGuideHost } from './WorkspaceGuideHost';
import type { WorkspaceGuide, UpdateWorkspaceGuideBody } from './api';
import {
  emitGuideEvent,
  notifyGuideEntryClicked,
  registerGuideSendPreparation,
  resetWorkspaceGuideBridge,
  subscribeChiefClosePanel,
  subscribeChiefOpenPanel,
  type ChiefOpenRequest,
} from './bridge';

const PROMPT = '请初始化当前工作空间，并引导我完成软件研发交付所需的配置。';

function envelope<T>(data: T) {
  return { success: true, code: '0', message: '', data, traceId: null };
}

function guide(overrides: Partial<WorkspaceGuide> = {}): WorkspaceGuide {
  return {
    eligible: true,
    status: 'IN_PROGRESS',
    stage: 1,
    substep: 0,
    conversationId: null,
    clientMessageId: null,
    version: 1,
    globallyDisabled: false,
    capabilityReady: true,
    ...overrides,
  };
}

let putBodies: UpdateWorkspaceGuideBody[] = [];
let skipCalls: number[] = [];
let currentGuide: WorkspaceGuide = guide();
let advanceFailure: { status: number; body: { success: boolean; code: string; message: string; traceId: string | null } } | null = null;
let opens: ChiefOpenRequest[] = [];
let closes = 0;
let clients: QueryClient[] = [];

function serveGuide(initial: WorkspaceGuide) {
  currentGuide = initial;
  server.use(http.get('/api/workspace-guide', () => HttpResponse.json(envelope(currentGuide))));
}

function serveAdvance() {
  server.use(http.put('/api/workspace-guide', async ({ request }) => {
    const body = await request.json() as UpdateWorkspaceGuideBody;
    putBodies.push(body);
    if (advanceFailure) {
      return HttpResponse.json(advanceFailure.body, { status: advanceFailure.status });
    }
    currentGuide = {
      ...currentGuide,
      status: body.status,
      stage: body.stage,
      substep: body.substep,
      conversationId: body.conversationId ?? currentGuide.conversationId,
      clientMessageId: body.clientMessageId ?? currentGuide.clientMessageId,
      version: body.version + 1,
    };
    return HttpResponse.json(envelope(currentGuide));
  }));
}

function serveSkip(result: 'ok' | 'fail' = 'ok') {
  server.use(http.post('/api/workspace-guide/skip', async ({ request }) => {
    const body = await request.json() as { version: number };
    skipCalls.push(body.version);
    if (result === 'fail') {
      return HttpResponse.json({ success: false, code: '50000', message: 'bad', traceId: null }, { status: 500 });
    }
    currentGuide = { ...currentGuide, status: 'SKIPPED', version: body.version + 1 };
    return HttpResponse.json(envelope(currentGuide));
  }));
}

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  return render(<QueryClientProvider client={client}><WorkspaceGuideHost /></QueryClientProvider>);
}

beforeEach(() => {
  resetWorkspaceGuideBridge();
  opens = [];
  closes = 0;
  putBodies = [];
  skipCalls = [];
  advanceFailure = null;
  subscribeChiefOpenPanel(request => opens.push(request));
  subscribeChiefClosePanel(() => { closes += 1; });
  useAuthStore.getState().clear();
  useAuthStore.getState().setUser({ id: 7, username: 'owner', nickname: 'Owner', email: '' });
  useAuthStore.getState().setCurrentWorkspace({ id: 1, name: '新空间', description: '' }, 'ADMIN');
  serveAdvance();
});

afterEach(() => {
  cleanup();
  clients.forEach(client => client.clear());
  clients = [];
});

describe('WorkspaceGuideHost', () => {
  it('revisits previous setup steps without moving saved progress backwards', async () => {
    serveGuide(guide({ stage: 2 }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '上一步' }));
    expect(screen.getByRole('dialog')).toHaveAttribute('data-stage', '1');
    expect(opens.at(-1)?.expanded).toBe(false);
    act(() => emitGuideEvent({ type: 'setup-ready' }));
    expect(screen.getByRole('dialog')).toHaveAttribute('data-stage', '1');
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    expect(screen.getByRole('dialog')).toHaveAttribute('data-stage', '2');
    expect(putBodies).toEqual([]);
  });

  it('connects the guide to the Chief entry and updates the curve after a resize', async () => {
    serveGuide(guide({ stage: 2 }));
    const entry = document.createElement('button');
    entry.dataset.guideTarget = 'entry';
    document.body.appendChild(entry);
    let entryTop = 24;
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return this === entry ? new DOMRect(900, entryTop, 32, 32) : new DOMRect(0, 0, 520, 260);
    });
    try {
      mount();
      const card = await screen.findByRole('dialog');
      expect(card.querySelector('.aw-guide-pointer')).toBeNull();
      const connector = document.querySelector('.aw-guide-connector');
      expect(connector).toHaveAttribute('aria-hidden', 'true');
      const path = connector?.querySelector('.aw-guide-connector-line');
      const initial = path?.getAttribute('d');
      expect(initial).toContain('C');
      expect(connector?.querySelector('.aw-guide-connector-arrow')).toBeTruthy();
      entryTop = window.innerHeight - 40;
      fireEvent(window, new Event('resize'));
      await waitFor(() => expect(path?.getAttribute('d')).not.toBe(initial));
    } finally {
      rect.mockRestore();
      entry.remove();
    }
  });

  it('shows the launch command failure inside the guide card', async () => {
    serveGuide(guide({ stage: 1, substep: 3 }));
    mount();
    const card = await screen.findByRole('dialog');
    act(() => emitGuideEvent({ type: 'setup-error', substep: 3, message: 'Launch configuration unavailable' }));
    expect(card).toHaveTextContent('Launch configuration unavailable');
    act(() => emitGuideEvent({ type: 'setup-error', substep: 3, message: null }));
    expect(card).not.toHaveTextContent('Launch configuration unavailable');
  });

  it('shows nothing when the workspace has no eligibility', async () => {
    serveGuide(guide({ eligible: false, status: null, stage: null, substep: null, version: null }));
    mount();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(opens).toHaveLength(0);
  });

  it('stays hidden for skipped, completed, or globally disabled workspaces', async () => {
    serveGuide(guide({ status: 'SKIPPED' }));
    mount();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    cleanup();
    serveGuide(guide({ globallyDisabled: true }));
    mount();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('starts the first phase only after clicking the setup button', async () => {
    serveGuide(guide({ status: 'PENDING', stage: 1, substep: 0, version: 0 }));
    mount();
    const start = await screen.findByRole('button', { name: '开始配置 Chief' });
    expect(opens).toHaveLength(0);
    fireEvent.click(start);
    await waitFor(() => expect(putBodies).toEqual([expect.objectContaining({
      status: 'IN_PROGRESS', stage: 1, substep: 0, version: 0,
    })]));
    expect(opens[opens.length - 1]).toMatchObject({ expanded: false, conversationId: null });
    expect(await screen.findByText('第1/4阶段 · 当前配置子步骤1/6')).toBeInTheDocument();
  });

  it('persists setup substeps, moves to phase 2 on real readiness, and collapses Chief', async () => {
    serveGuide(guide({ status: 'IN_PROGRESS', stage: 1, substep: 0, version: 3 }));
    mount();
    await screen.findByRole('button', { name: /跳\s*过/ });
    act(() => emitGuideEvent({ type: 'substep-completed' }));
    await waitFor(() => expect(putBodies[0]).toMatchObject({ stage: 1, substep: 1, version: 3 }));
    await waitFor(() => expect(screen.getByText('第1/4阶段 · 当前配置子步骤2/6')).toBeInTheDocument());

    act(() => emitGuideEvent({ type: 'setup-ready' }));
    await waitFor(() => expect(putBodies[1]).toMatchObject({ stage: 2, substep: 0 }));
    await waitFor(() => expect(closes).toBeGreaterThan(0));
    expect(await screen.findByText('进入 Chief')).toBeInTheDocument();
  });

  it('opens the same Chief expanded, preflights the prompt, and reports a blocked draft', async () => {
    serveGuide(guide({ stage: 2, version: 5 }));
    registerGuideSendPreparation(vi.fn().mockResolvedValue({ conversationId: 88, clientMessageId: 'message-1' }));
    mount();
    await screen.findByText('进入 Chief');
    act(() => notifyGuideEntryClicked());
    await waitFor(() => expect(putBodies[0]).toMatchObject({
      stage: 3, status: 'IN_PROGRESS', conversationId: 88, clientMessageId: 'message-1', version: 5,
    }));
    await waitFor(() => expect(opens[opens.length - 1]).toMatchObject({
      expanded: true, conversationId: 88, clientMessageId: 'message-1', prefill: PROMPT,
    }));
    act(() => opens[opens.length - 1].onPrefillResult?.(false));
    expect(await screen.findByText('已有草稿，未覆盖。请先发送或清理草稿后点击重试。')).toBeInTheDocument();
  });

  it('prefills once on the normal phase 2 entry and never reports a blocked draft', async () => {
    serveGuide(guide({ stage: 2, version: 5 }));
    registerGuideSendPreparation(vi.fn().mockResolvedValue({ conversationId: 88, clientMessageId: 'message-1' }));
    mount();
    await screen.findByText('进入 Chief');

    act(() => {
      notifyGuideEntryClicked();
      notifyGuideEntryClicked();
    });
    await waitFor(() => expect(putBodies).toHaveLength(1));
    await waitFor(() => expect(opens.filter(request => request.prefill !== null)).toHaveLength(1));
    const prefillOpen = opens.find(request => request.prefill !== null)!;
    act(() => prefillOpen.onPrefillResult?.(true));
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(opens.filter(request => request.prefill !== null)).toHaveLength(1);
    expect(screen.queryByText('已有草稿，未覆盖。请先发送或清理草稿后点击重试。')).not.toBeInTheDocument();
  });

  it('blocks the handoff when the initialization capability is missing', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1', capabilityReady: false }));
    registerGuideSendPreparation(vi.fn());
    mount();
    expect(await screen.findByText('空间初始化能力尚未就绪，暂不能交接。请稍后重试。')).toBeInTheDocument();
    expect(opens[opens.length - 1]).toMatchObject({ expanded: true, conversationId: 88, prefill: PROMPT });

    act(() => emitGuideEvent({ type: 'send-succeeded', conversationId: 88, clientMessageId: 'message-1' }));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(putBodies).toHaveLength(0);
    expect(screen.getByText('发送初始化请求')).toBeInTheDocument();
  });

  it('advances to the handoff phase after a real send', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1' }));
    registerGuideSendPreparation(vi.fn());
    mount();
    await screen.findByText('发送初始化请求');
    act(() => emitGuideEvent({ type: 'send-succeeded', conversationId: 88, clientMessageId: 'message-1' }));
    await waitFor(() => expect(putBodies[0]).toMatchObject({ stage: 4, conversationId: 88, clientMessageId: 'message-1' }));
    expect(await screen.findByText('Chief 接力')).toBeInTheDocument();
  });

  it('keeps a retry path when saving the accepted send fails', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1' }));
    registerGuideSendPreparation(vi.fn());
    advanceFailure = { status: 500, body: { success: false, code: '50000', message: 'down', traceId: null } };
    mount();
    await screen.findByRole('dialog');
    act(() => emitGuideEvent({ type: 'send-succeeded', conversationId: 88, clientMessageId: 'message-1' }));
    expect(await screen.findByText('引导进度保存失败，请刷新后重试。')).toBeInTheDocument();
    advanceFailure = null;
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(putBodies[putBodies.length - 1]).toMatchObject({ stage: 4, conversationId: 88 }));
  });

  it('finishes the guide only when confirming the last phase', async () => {
    serveGuide(guide({ stage: 4, version: 9 }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    await waitFor(() => expect(putBodies[0]).toMatchObject({ status: 'COMPLETED', stage: 4, version: 9 }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('skips only the current workspace and reports a failed save', async () => {
    serveGuide(guide({ stage: 2, version: 4 }));
    serveSkip('fail');
    mount();
    fireEvent.click(await screen.findByRole('button', { name: /跳\s*过/ }));
    expect(await screen.findByText('保存跳过状态失败，请重试。')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    serveSkip('ok');
    fireEvent.click(screen.getByRole('button', { name: /跳\s*过/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(skipCalls).toEqual([4, 4]);
  });

  it('confirms the account-level disable only after the preference is saved', async () => {
    serveGuide(guide({ stage: 2, version: 4 }));
    mount();
    const never = await screen.findByRole('checkbox', { name: '不再提示' });
    fireEvent.click(never);
    expect(screen.getByText('确认后，当前及今后新建空间都不再显示此引导。后续可通过顶部平台Chief入口完成空间初始化配置。')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(screen.queryByText('确认后，当前及今后新建空间都不再显示此引导。后续可通过顶部平台Chief入口完成空间初始化配置。')).not.toBeInTheDocument();
    expect((screen.getByRole('checkbox', { name: '不再提示' }) as HTMLInputElement).checked).toBe(false);

    fireEvent.click(screen.getByRole('checkbox', { name: '不再提示' }));
    fireEvent.click(screen.getByRole('button', { name: '确认并关闭' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('keeps the dialog open with a retry message when saving the disable preference fails', async () => {
    serveGuide(guide({ stage: 2, version: 4 }));
    server.use(http.put('/api/users/me/settings/:key', () =>
      HttpResponse.json({ success: false, code: '50000', message: 'down', traceId: null }, { status: 500 })));
    mount();
    fireEvent.click(await screen.findByRole('checkbox', { name: '不再提示' }));
    fireEvent.click(screen.getByRole('button', { name: '确认并关闭' }));
    expect(await screen.findByText('偏好保存失败，请重试。引导未关闭。')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认并关闭' })).toBeInTheDocument();
  });

  it('never forces the overlay when the preference read fails', async () => {
    server.use(http.get('/api/workspace-guide', () =>
      HttpResponse.json({ success: false, code: '50000', message: 'down', traceId: null }, { status: 500 })));
    mount();
    expect(await screen.findByText('无法读取新空间引导偏好，引导未展示。')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('开始配置 Chief')).not.toBeInTheDocument();

    serveGuide(guide({ stage: 2, version: 4 }));
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('进入 Chief')).toBeInTheDocument();
  });

  it('keeps the guide on the current step when a version conflict is reported', async () => {
    serveGuide(guide({ stage: 1, version: 4 }));
    advanceFailure = { status: 400, body: { success: false, code: '40002', message: 'conflict', traceId: null } };
    mount();
    await screen.findByRole('dialog');
    act(() => emitGuideEvent({ type: 'substep-completed' }));
    expect(await screen.findByText('引导进度已更新，已按最新状态继续。')).toBeInTheDocument();
    expect(screen.getAllByText('配置 Chief').length).toBeGreaterThan(0);
  });

  it('ignores a late response for the workspace that was left', async () => {
    let calls = 0;
    let releaseFirst: (() => void) | null = null;
    const firstGate = new Promise<void>(resolve => { releaseFirst = resolve; });
    server.use(http.get('/api/workspace-guide', async () => {
      calls += 1;
      if (calls === 1) {
        await firstGate;
        return HttpResponse.json(envelope(guide({ stage: 1, version: 9 })));
      }
      return HttpResponse.json(envelope(guide({ eligible: false, status: null, stage: null, substep: null, version: null })));
    }));
    mount();
    await waitFor(() => expect(calls).toBe(1));
    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: '另一个空间', description: '' }, 'ADMIN'));
    await waitFor(() => expect(calls).toBe(2));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    act(() => { releaseFirst?.(); });
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('retries opening the guided conversation after a blocked draft', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1' }));
    registerGuideSendPreparation(vi.fn());
    mount();
    await screen.findByText('发送初始化请求');
    const opened = opens[opens.length - 1];
    act(() => opened.onPrefillResult?.(false));
    expect(await screen.findByText('已有草稿，未覆盖。请先发送或清理草稿后点击重试。')).toBeInTheDocument();
    const before = opens.length;

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(opens.length).toBeGreaterThan(before));
  });

  it('reports a failed conversation preparation and keeps the guide on phase 2', async () => {
    serveGuide(guide({ stage: 2, version: 5 }));
    registerGuideSendPreparation(vi.fn().mockRejectedValue(new Error('offline')));
    mount();
    await screen.findByText('进入 Chief');

    act(() => notifyGuideEntryClicked());

    expect(await screen.findByText('无法准备初始化对话，请重试。')).toBeInTheDocument();
    expect(putBodies).toHaveLength(0);
    expect(screen.getByText('进入 Chief')).toBeInTheDocument();
  });

  it('reports a failed guided send without advancing', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1' }));
    registerGuideSendPreparation(vi.fn());
    mount();
    await screen.findByText('发送初始化请求');

    act(() => emitGuideEvent({ type: 'send-failed' }));

    expect(await screen.findByText('发送失败，草稿已保留，请重试。')).toBeInTheDocument();
    expect(putBodies).toHaveLength(0);
  });

  it('ignores an entry click outside phase 2', async () => {
    serveGuide(guide({ stage: 3, version: 6, conversationId: 88, clientMessageId: 'message-1' }));
    registerGuideSendPreparation(vi.fn());
    mount();
    await screen.findByText('发送初始化请求');
    const opened = opens.length;
    const persisted = putBodies.length;

    act(() => notifyGuideEntryClicked());
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(opens.length).toBe(opened);
    expect(putBodies.length).toBe(persisted);
  });

  it('offers retry when phase 3 progress cannot be saved', async () => {
    serveGuide(guide({ stage: 3, version: 6 }));
    registerGuideSendPreparation(vi.fn().mockResolvedValue({ conversationId: 88, clientMessageId: 'message-1' }));
    advanceFailure = { status: 500, body: { success: false, code: '50000', message: 'down', traceId: null } };
    mount();
    expect(await screen.findByText('引导进度保存失败，请刷新后重试。')).toBeInTheDocument();

    advanceFailure = null;
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(putBodies[putBodies.length - 1]).toMatchObject({
      stage: 3, conversationId: 88, clientMessageId: 'message-1',
    }));
    await waitFor(() => expect(opens[opens.length - 1]).toMatchObject({ expanded: true, conversationId: 88 }));
  });

  it('drops a progress response that arrives after the workspace changed', async () => {
    serveGuide(guide({ stage: 1, substep: 0, version: 2 }));
    const gateHolder: { release?: () => void } = {};
    const putGate = new Promise<void>(resolve => { gateHolder.release = resolve; });
    server.use(http.put('/api/workspace-guide', async ({ request }) => {
      const body = await request.json() as UpdateWorkspaceGuideBody;
      putBodies.push(body);
      await putGate;
      return HttpResponse.json(envelope({ ...currentGuide, stage: 1, substep: 1, version: 3 }));
    }));
    mount();
    await screen.findByRole('dialog');
    act(() => emitGuideEvent({ type: 'substep-completed' }));
    await waitFor(() => expect(putBodies).toHaveLength(1));

    act(() => useAuthStore.getState().setCurrentWorkspace({ id: 2, name: '另一个空间', description: '' }, 'ADMIN'));
    serveGuide(guide({ eligible: false, status: null, stage: null, substep: null, version: null }));
    act(() => { gateHolder.release?.(); });
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(screen.queryByText('引导进度保存失败，请刷新后重试。')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not skip when the stored progress has no version', async () => {
    serveGuide(guide({ stage: 2, version: null }));
    mount();
    await screen.findByText('进入 Chief');

    fireEvent.click(screen.getByRole('button', { name: /跳\s*过/ }));
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(skipCalls).toHaveLength(0);
  });

  it('ignores guide events that do not match the current stage or exceed the last substep', async () => {
    serveGuide(guide({ stage: 1, substep: 5, version: 3 }));
    mount();
    await screen.findByRole('dialog');

    act(() => emitGuideEvent({ type: 'substep-completed' }));
    act(() => emitGuideEvent({ type: 'send-succeeded', conversationId: 88, clientMessageId: 'message-1' }));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(putBodies).toHaveLength(0);

    act(() => {
      emitGuideEvent({ type: 'setup-ready' });
      emitGuideEvent({ type: 'setup-ready' });
    });
    await waitFor(() => expect(putBodies.map(body => [body.stage, body.substep])).toEqual([[2, 0]]));
    expect(putBodies[0]).toMatchObject({ stage: 2 });
  });

  it('keeps the guide open when starting it cannot be saved', async () => {
    serveGuide(guide({ status: 'PENDING', stage: 1, substep: 0, version: 0 }));
    advanceFailure = { status: 500, body: { success: false, code: '50000', message: 'down', traceId: null } };
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '开始配置 Chief' }));

    expect(await screen.findByText('引导进度保存失败，请刷新后重试。')).toBeInTheDocument();
    expect(opens).toHaveLength(0);
  });
});
