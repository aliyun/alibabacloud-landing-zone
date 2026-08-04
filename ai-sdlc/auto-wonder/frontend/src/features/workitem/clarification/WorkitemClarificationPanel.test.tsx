import { describe, it, expect, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemClarificationPanel } from './WorkitemClarificationPanel';
import {
  clearClarificationPrefill,
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';

function renderPanel(agents: Array<{ agentId: number; agentName: string; status: string }> = []) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkitemClarificationPanel
        workitemId="100"
        agents={agents as never}
      />
    </QueryClientProvider>,
  );
}

function mockSquads() {
  server.use(
    http.get('/api/squads', () =>
      HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: {
          list: [{ id: 9, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }],
          total: 1,
          pageNum: 1,
          pageSize: 100,
        },
      }),
    ),
    http.get('/api/squads/:squadId/members', () =>
      HttpResponse.json({
        success: true,
        code: '0',
        message: '',
        traceId: null,
        data: [{ agentId: 42, agentName: 'Agent-X', roleCode: 'AW_FS_DEV' }],
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

function mockConversationWithTurns(agentId: number = 42) {
  server.use(
    http.get('/api/workitems/:workitemId/clarification-conversations', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: [{
          id: 1, agentId, agentName: 'Agent-X', channelConversationId: 'ch-1',
          status: 'ACTIVE', executorOnline: true, streamingSupported: true,
          cliSessionRef: null, processingStatus: null, processingTurnId: null,
          lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
          turns: [
            { id: 1, direction: 'INBOUND', content: '你好，我想讨论需求', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
            { id: 2, direction: 'OUTBOUND', content: '好的，请说说你的想法', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
          ],
        }],
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
      HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          id: 1, agentId, agentName: 'Agent-X', channelConversationId: 'ch-1',
          status: 'ACTIVE', executorOnline: true, streamingSupported: true,
          cliSessionRef: null, processingStatus: null, processingTurnId: null,
          lastTurnAt: '2026-01-01T00:00:00', gmtCreate: '2026-01-01T00:00:00',
          turns: [
            { id: 1, direction: 'INBOUND', content: '你好，我想讨论需求', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:00' },
            { id: 2, direction: 'OUTBOUND', content: '好的，请说说你的想法', status: 'SUCCESS', error: null, gmtCreate: '2026-01-01T00:00:01' },
          ],
        },
      }),
    ),
    http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId/events', () =>
      HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] }),
    ),
  );
}

describe('WorkitemClarificationPanel', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('uses delivery agents when progress already provides them', () => {
    renderPanel([
      { agentId: 1, agentName: 'Agent-A', status: 'active' },
      { agentId: 2, agentName: 'Agent-B', status: 'pending' },
    ]);
    expect(screen.getByText('选择数字人')).toBeInTheDocument();
    expect(screen.getByText('Agent-A')).toBeInTheDocument();
  });

  it('falls back to squad selector when no delivery agents exist', async () => {
    mockSquads();
    renderPanel([]);
    expect(screen.getByText('选择小队和数字人')).toBeInTheDocument();
    // Antd Select renders its placeholder inside a span, not an input attribute;
    // wait for the squad input to become enabled (squads loaded).
    const comboboxes = await screen.findAllByRole('combobox');
    expect(comboboxes.length).toBeGreaterThanOrEqual(2);
    await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
  });

  it('persists the selection to localStorage for delivery prefill', async () => {
    mockSquads();
    renderPanel([]);
    const comboboxes = await screen.findAllByRole('combobox');
    await waitFor(() => expect(comboboxes[0]).not.toBeDisabled());
    fireEvent.mouseDown(comboboxes[0]);
    fireEvent.click(await screen.findByText('交付小队'));
    await waitFor(() => expect(comboboxes[1]).not.toBeDisabled());
    fireEvent.mouseDown(comboboxes[1]);
    fireEvent.click(await screen.findByText('Agent-X (AW_FS_DEV)'));
    await waitFor(() => {
      const prefill = readClarificationPrefill('100');
      expect(prefill).not.toBeNull();
      expect(prefill!.squadId).toBe(9);
      expect(prefill!.agentId).toBe(42);
    });
  });

  it('prefills from localStorage when re-opened', () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    renderPanel([]);
    // The prefilled selection short-circuits the agent-selection screen.
    expect(screen.queryByText('选择小队和数字人')).not.toBeInTheDocument();
  });

  it('shows the selected conversation agent name when delivery has not started', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    server.use(
      http.get('/api/workitems/:workitemId/clarification-conversations', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [{ id: 7 }] }),
      ),
      http.get('/api/workitems/:workitemId/clarification-conversations/:conversationId', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            id: 7,
            agentId: 42,
            agentName: '已选择的开发',
            channelConversationId: 'clarification-7',
            status: 'ACTIVE',
            executorOnline: true,
            streamingSupported: true,
            cliSessionRef: null,
            processingStatus: null,
            processingTurnId: null,
            lastTurnAt: null,
            gmtCreate: '',
            turns: [],
          },
        }),
      ),
    );

    renderPanel([]);

    expect(await screen.findByText('已选择的开发')).toBeInTheDocument();
    expect(screen.queryByText('数字人')).not.toBeInTheDocument();
    expect(await screen.findByDisplayValue(
      '请通过 AutoWonder MCP 读取工单 #100，与我进行需求澄清。',
    )).toBeInTheDocument();
  });

  it('clears prefill for unrelated workitems', () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    clearClarificationPrefill('100');
    expect(readClarificationPrefill('100')).toBeNull();
  });

  it('renders sender labels for user and AI messages', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    expect(screen.getByText('你')).toBeInTheDocument();
    expect(screen.getByText('好的，请说说你的想法')).toBeInTheDocument();
  });

  it('distinguishes user and AI messages with different styles', async () => {
    writeClarificationPrefill('100', { squadId: 9, agentId: 42 });
    mockSquads();
    mockConversationWithTurns(42);
    renderPanel([]);

    await waitFor(() => {
      expect(screen.getByText('你好，我想讨论需求')).toBeInTheDocument();
    });

    const userMsg = screen.getByText('你好，我想讨论需求').closest('div');
    const aiMsg = screen.getByText('好的，请说说你的想法').closest('div');
    expect(userMsg).not.toBeNull();
    expect(aiMsg).not.toBeNull();
    expect(userMsg!.style.backgroundColor).toBe('rgb(230, 247, 255)');
    expect(aiMsg!.style.backgroundColor).toBe('rgb(245, 245, 245)');
  });
});
