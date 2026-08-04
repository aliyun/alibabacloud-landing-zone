import { act } from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiSessionPanel } from './AiSessionPanel';
import { useAuthStore } from '@/shared/auth/store';
import { message } from 'antd';

const realtimeMock = vi.hoisted(() => ({
  onEvent: null as ((event: { payload: unknown }) => void) | null,
}));

vi.mock('@/shared/realtime/useRealtime', () => ({
  useRealtime: (_channel: string | null, options: { onEvent: (event: { payload: unknown }) => void; enabled?: boolean }) => {
    realtimeMock.onEvent = options.enabled === false ? null : options.onEvent;
  },
}));

function renderPanel(props = {}, accessLevel: 'READ_ONLY' | 'READ_WRITE' = 'READ_WRITE') {
  useAuthStore.setState({ accessLevel });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AiSessionPanel scene="CLARIFICATION" bizRefType="WORKITEM" bizRefId={1} {...props} />
    </QueryClientProvider>,
  );
}

describe('AiSessionPanel', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/ai/sessions', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: 100 }),
      ),
      http.get('/api/ai/sessions/100', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'CLARIFICATION', bizRefType: 'WORKITEM', bizRefId: 1,
            status: 'RUNNING', resultJson: null, error: null, gmtCreate: '2026-01-01',
            messages: [{ id: 1, sessionId: 100, role: 'USER', content: 'hello', gmtCreate: '2026-01-01' }],
          },
        }),
      ),
    );
  });

  it('renders input area for starting conversation', () => {
    renderPanel();
    expect(screen.getByPlaceholderText(/输入/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发送/ })).toBeInTheDocument();
  });

  it('creates session then loads full state via GET /{id}', async () => {
    renderPanel();
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '分析一下需求');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => {
      expect(screen.getByText('hello')).toBeInTheDocument();
    });
  });

  it('keeps send visible but does not create a session for read-only members', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let createRequests = 0;
    server.use(
      http.post('/api/ai/sessions', () => {
        createRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: 100 });
      }),
    );

    renderPanel({}, 'READ_ONLY');
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '分析一下需求');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，发送 AI 会话消息需要读写权限');
    expect(createRequests).toBe(0);
    errorSpy.mockRestore();
  });

  it('shows clarification bootstrap text while the first response is still running', async () => {
    server.use(
      http.get('/api/ai/sessions/100', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'CLARIFICATION', bizRefType: 'WORKITEM', bizRefId: 1,
            status: 'RUNNING', resultJson: null, error: null, gmtCreate: '2026-01-01',
            messages: [],
          },
        }),
      ),
    );

    renderPanel();
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '分析一下需求');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('正在准备上下文，更好的跟你探讨...')).toBeInTheDocument();
  });

  it('shows renderer + confirm when session is WAIT_USER with resultJson', async () => {
    server.use(
      http.get('/api/ai/sessions/100', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1,
            status: 'WAIT_USER',
            resultJson: JSON.stringify({ purpose: '订单', keyBusiness: [], upstreams: [], downstreams: [], summaryMd: '# x' }),
            error: null, gmtCreate: '', messages: [],
          },
        }),
      ),
    );
    renderPanel({ scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1 });
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '扫描');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => {
      expect(screen.getByDisplayValue('订单')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /确认落库/ })).toBeInTheDocument();
  });

  it('does not render a raw structured result json as a long AI chat message', async () => {
    const resultJson = JSON.stringify({
      name: '研发数字员工内部SDLC工作流',
      description: '流程说明',
      steps: [
        { order: 1, name: '需求满足性分析', kind: 'analysis', instructionMd: '分析上下文是否足够。' },
      ],
    });
    server.use(
      http.get('/api/ai/sessions/100', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'SDLC_GEN', bizRefType: 'ORG', bizRefId: 0,
            status: 'WAIT_USER',
            resultJson,
            error: null, gmtCreate: '',
            messages: [
              { id: 1, sessionId: 100, role: 'AI', content: resultJson, gmtCreate: '2026-01-01' },
            ],
          },
        }),
      ),
    );

    renderPanel({ scene: 'SDLC_GEN', bizRefType: 'ORG', bizRefId: 0 });
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '生成');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('已生成结构化结果，请在右侧预览和确认。')).toBeInTheDocument();
    expect(screen.queryByText(/\{\s*"name":\s*"研发数字员工内部SDLC工作流"/)).not.toBeInTheDocument();
  });

  it('does not retry auto-start after create session fails', async () => {
    let createAttempts = 0;
    server.use(
      http.post('/api/ai/sessions', () => {
        createAttempts += 1;
        return HttpResponse.json(
          { success: false, code: '20010', message: '仓库正在扫描中', traceId: null, data: null },
          { status: 409 },
        );
      }),
    );

    renderPanel({
      scene: 'REPO_SCAN',
      bizRefType: 'REPO',
      bizRefId: 10000,
      autoStartInput: '请扫描仓库 auto-wonder',
    });

    expect(await screen.findByText('仓库正在扫描中')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(createAttempts).toBe(1);
    expect(screen.queryByText('正在创建 AI 扫描会话...')).not.toBeInTheDocument();
  });

  it('renders stream tokens and appends the final AI message when realtime streaming completes', async () => {
    server.use(
      http.get('/api/ai/sessions/100', () =>
        HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'CLARIFICATION', bizRefType: 'WORKITEM', bizRefId: 1,
            status: 'RUNNING', resultJson: null, error: null, gmtCreate: '2026-01-01', messages: [],
          },
        }),
      ),
    );

    renderPanel();
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '分析一下需求');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await act(async () => {
      realtimeMock.onEvent?.({ payload: { type: 'delta', text: '正在分析' } });
    });
    expect(await screen.findByText('正在分析')).toBeInTheDocument();

    await act(async () => {
      realtimeMock.onEvent?.({
        payload: {
          type: 'ai_message_done',
          message: {
            id: 2,
            sessionId: 100,
            role: 'AI',
            content: '分析完成',
            gmtCreate: '2026-01-01',
          },
        },
      });
    });

    expect(await screen.findByText('分析完成')).toBeInTheDocument();
    expect(screen.queryByText('正在分析')).not.toBeInTheDocument();
  });

  it('restores the latest persisted session instead of faking WAIT_USER when a follow-up message fails', async () => {
    let getCount = 0;
    server.use(
      http.get('/api/ai/sessions/100', () => {
        getCount += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: getCount === 1
            ? {
                id: 100, scene: 'CLARIFICATION', bizRefType: 'WORKITEM', bizRefId: 1,
                status: 'WAIT_USER', resultJson: JSON.stringify({ summary: 'x' }), error: null, gmtCreate: '2026-01-01',
                messages: [{ id: 1, sessionId: 100, role: 'AI', content: '请补充上下文', gmtCreate: '2026-01-01' }],
              }
            : {
                id: 100, scene: 'CLARIFICATION', bizRefType: 'WORKITEM', bizRefId: 1,
                status: 'COMPLETED', resultJson: JSON.stringify({ summary: 'x' }), error: null, gmtCreate: '2026-01-01',
                messages: [{ id: 1, sessionId: 100, role: 'AI', content: '请补充上下文', gmtCreate: '2026-01-01' }],
              },
        });
      }),
      http.post('/api/ai/sessions/100/messages', () =>
        HttpResponse.json(
          { success: false, code: '19003', message: 'AI会话非待确认状态', traceId: null, data: null },
          { status: 409 },
        ),
      ),
    );

    renderPanel();
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '分析一下需求');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('请补充上下文')).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText(/输入/), '补充一下接口约束');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => {
      expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    });
    expect(screen.queryByText('补充一下接口约束')).not.toBeInTheDocument();
  });

  it('refreshes WAIT_USER structured result when a realtime result event arrives', async () => {
    const resultJson = JSON.stringify({
      purpose: '订单',
      keyBusiness: ['下单'],
      upstreams: [],
      downstreams: [],
      summaryMd: '# 订单',
    });
    let getCount = 0;
    server.use(
      http.get('/api/ai/sessions/100', () => {
        getCount += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: getCount === 1
            ? {
                id: 100, scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1,
                status: 'RUNNING', resultJson: null, error: null, gmtCreate: '2026-01-01', messages: [],
              }
            : {
                id: 100, scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1,
                status: 'WAIT_USER', resultJson, error: null, gmtCreate: '2026-01-01', messages: [],
              },
        });
      }),
    );

    renderPanel({ scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1 });
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '扫描');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => {
      expect(screen.getByText('AI 正在思考...')).toBeInTheDocument();
    });

    await act(async () => {
      realtimeMock.onEvent?.({
        payload: {
          type: 'result',
          sessionId: 100,
          resultJson,
        },
      });
    });

    await waitFor(() => {
      expect(getCount).toBe(2);
      expect(screen.getByDisplayValue('订单')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /确认落库/ })).toBeInTheDocument();
  });

  it('shows completed feedback after confirming a WAIT_USER result', async () => {
    const resultJson = JSON.stringify({
      purpose: '订单',
      keyBusiness: [],
      upstreams: [],
      downstreams: [],
      summaryMd: '# x',
    });
    let getCount = 0;
    server.use(
      http.get('/api/ai/sessions/100', () => {
        getCount += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            id: 100, scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1,
            status: getCount > 1 ? 'COMPLETED' : 'WAIT_USER',
            resultJson, error: null, gmtCreate: '2026-01-01', messages: [],
          },
        });
      }),
      http.post('/api/ai/sessions/100/confirm', () =>
        HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null }),
      ),
    );

    const onConfirm = vi.fn();
    renderPanel({ scene: 'REPO_SCAN', bizRefType: 'REPO', bizRefId: 1, onConfirm });
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/输入/), '扫描');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByRole('button', { name: /确认落库/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /确认落库/ }));

    await waitFor(() => {
      expect(screen.getByText(/已生效/)).toBeInTheDocument();
      expect(onConfirm).toHaveBeenCalledWith(expect.stringContaining('订单'));
    });
    expect(screen.queryByRole('button', { name: /确认落库/ })).not.toBeInTheDocument();
  });
});
