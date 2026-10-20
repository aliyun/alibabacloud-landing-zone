import { i18n } from '@/shared/i18n';
import { act } from '@testing-library/react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RuntimeTraceDrawer, dispatchFailureReason } from './RuntimeTraceDrawer';

const usage = { available: false, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, totalTokens: 0 };

describe('RuntimeTraceDrawer', () => {
  it('lazy loads exact OSS turn and observation payloads', async () => {
    let turnRequests = 0;
    let observationRequests = 0;
    server.use(
      http.get('/api/dispatches/44/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        schemaVersion: 'autowonder.runtime-trace.v2', source: 'OSS', dispatchId: 44, provider: 'qoder', changed: true,
        tokenUsage: usage, events: [], sessions: [{
          sessionId: 'qoder-session', provider: 'qoder', status: 'COMPLETED', durationMs: 1200, tokenUsage: usage, boundaries: [], eventIds: [],
          turns: [{ traceId: 'turn:1', turnId: 'turn:1', status: 'COMPLETED', durationMs: 1200, providerCoverage: 'PARTIAL', tokenUsage: usage, usage, eventIds: [], spans: [],
            observations: [{ observationId: 'turn:1:provider', type: 'PROVIDER', name: 'qoder Provider Turn', children: [
              { observationId: 'thinking:1', parentObservationId: 'turn:1:provider', type: 'THINKING', name: 'Observed Thinking', durationMs: 420, children: [] },
              { observationId: 'wait:1', parentObservationId: 'turn:1:provider', type: 'WAIT', name: 'Model / Provider Wait', durationMs: 500, children: [] },
              { observationId: 'mcp:1', parentObservationId: 'turn:1:provider', type: 'MCP', name: 'code.search', durationMs: 280, children: [] },
            ] }], contextFiles: [] }],
        }],
      } })),
      http.get('/api/dispatches/44/runtime-trace/turns/turn%3A1', () => {
        turnRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: {
          traceId: 'turn:1', turnId: 'turn:1', status: 'COMPLETED', durationMs: 1200, providerCoverage: 'PARTIAL', tokenUsage: usage, usage,
          prompt: 'exact user prompt', systemPrompt: 'exact system prompt', output: 'agent answer', eventIds: [], spans: [], observations: [], contextFiles: [],
        } });
      }),
      http.get('/api/dispatches/44/runtime-trace/observations/mcp%3A1', () => {
        observationRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', data: {
          observationId: 'mcp:1', type: 'MCP', name: 'code.search', durationMs: 280, input: { query: 'posterior' }, output: 'exact tool result', usage, children: [],
        } });
      }),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:44', dispatchId: 44, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    fireEvent.click(await within(drawer).findByText(/Turn turn:1/));
    await waitFor(() => expect(turnRequests).toBe(1));
    expect(within(drawer).getByText('exact system prompt')).toBeInTheDocument();
    expect(within(drawer).getByText('exact user prompt')).toBeInTheDocument();
    expect(within(drawer).getByText(/部分覆盖/)).toBeInTheDocument();
    expect(within(drawer).getByText('Observed Thinking')).toBeInTheDocument();
    expect(within(drawer).getByText('Model / Provider Wait')).toBeInTheDocument();

    fireEvent.click(within(drawer).getByText('code.search'));
    await waitFor(() => expect(observationRequests).toBe(1));
    expect(within(drawer).getByText(/posterior/)).toBeInTheDocument();
    expect(within(drawer).getByText('exact tool result')).toBeInTheDocument();
  });

  const gateReason = 'step "400166" gate: evidence required but none provided';
  const failedTracePayload = {
    schemaVersion: 'autowonder.runtime-trace.v2', source: 'LIVE', dispatchId: 44, provider: 'qoder', changed: true,
    tokenUsage: usage,
    events: [
      { eventId: 'e-progress', eventType: 'progress', detail: {} },
      { eventId: 'e-failed', eventType: 'step.failed', detail: { reason: gateReason } },
    ],
    sessions: [],
  };

  it('shows the stored failure reason for a FAILED dispatch', async () => {
    server.use(
      http.get('/api/dispatches/44/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: failedTracePayload })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:44', dispatchId: 44, agentName: '开发', status: 'FAILED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const alert = await screen.findByTestId('dispatch-failure-reason');
    expect(within(alert).getByText(gateReason)).toBeInTheDocument();
    const drawer = screen.getByTestId('runtime-trace-drawer');
    expect(within(drawer).getAllByText(gateReason).length).toBeGreaterThanOrEqual(2);
  });

  it('surfaces the blocking tool hook instead of the missing completion request', async () => {
    const blockage = 'tool_hook_blocked: kind safety_denial hook jarvis-tool-safety trigger beforeTool status blocked exitCode 2 blockedCalls 3';
    server.use(
      http.get('/api/dispatches/44/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        ...failedTracePayload,
        events: [
          { eventId: 'e-progress', eventType: 'progress', detail: {} },
          { eventId: 'e-blocked', eventType: 'step.failed', detail: { reason: blockage } },
          { eventId: 'e-symptom', eventType: 'step.failed', detail: { reason: 'missing completion request' } },
        ],
      } })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:44', dispatchId: 44, agentName: '开发', status: 'FAILED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const alert = await screen.findByTestId('dispatch-failure-reason');
    expect(within(alert).getByText(blockage)).toBeInTheDocument();
    expect(within(alert).queryByText('missing completion request')).not.toBeInTheDocument();
  });

  it('does not show a failure alert for a non-failed dispatch', async () => {
    server.use(
      http.get('/api/dispatches/44/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: failedTracePayload })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:44', dispatchId: 44, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    await screen.findByText('Runtime 与 SDLC');
    expect(screen.queryByText('Dispatch 失败原因')).not.toBeInTheDocument();
  });

  it('renders the bounded failure history of every failed round', async () => {
    server.use(
      http.get('/api/dispatches/44/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        ...failedTracePayload,
        events: [
          ...failedTracePayload.events,
          { eventId: 'e-history', eventType: 'dispatch.failed', eventTime: '2026-10-15T03:41:07.331Z', detail: {
            state: 'FAILED',
            error: 'workflow routing preflight failed: qoder semantic inactivity timeout after 10m0s',
            failureHistory: { truncated: false, rounds: [
              { attemptIndex: 1, phase: 'routing_preflight', provider: 'qoder', model: 'dmodel',
                sessionId: '689186f5-a6bd-4816-9fa1-6bff68e7b896', status: 'failed',
                error: 'qoder turn failed: RESOURCE_LIMIT 10605 model_queued Queuing failed',
                diagnostics: [{ source: 'provider_log', category: 'business', code: '10605', subCode: 'model_queued',
                  businessCode: 'RESOURCE_LIMIT', message: 'Queuing failed', occurrences: 2,
                  requestId: 'f203f510-c793-4e82-b64d-18c879abb08c' }] },
              { attemptIndex: 2, phase: 'routing_preflight', provider: 'qoder', model: 'dmodel',
                sessionId: 'aa2db1e7-4256-49eb-ae7b-6faea387b7e0', status: 'failed',
                error: 'qoder semantic inactivity timeout after 10m0s' },
            ] },
          } },
        ],
      } })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:44', dispatchId: 44, agentName: '开发', status: 'FAILED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const panel = await screen.findByTestId('dispatch-failure-history');
    expect(within(panel).getByText('共 2 轮失败')).toBeInTheDocument();
    expect(within(panel).getByTestId('failure-history-round-error-1')).toHaveTextContent('RESOURCE_LIMIT 10605 model_queued Queuing failed');
    expect(within(panel).getByTestId('failure-history-round-error-2')).toHaveTextContent('semantic inactivity timeout after 10m0s');
    fireEvent.click(within(panel).getByRole('button', { name: '展开第 1 轮失败详情' }));
    fireEvent.click(within(panel).getByRole('button', { name: '展开第 1 条诊断' }));
    expect(within(panel).getByText('f203f510-c793-4e82-b64d-18c879abb08c')).toBeInTheDocument();
    expect(within(panel).getByText('689186f5-a6bd-4816-9fa1-6bff68e7b896')).toBeInTheDocument();
    expect(await screen.findByTestId('dispatch-failure-reason')).toBeInTheDocument();
  });
});

describe('RuntimeTraceDrawer usage display', () => {
  function tracePayload(tokenUsage: unknown) {
    return {
      schemaVersion: 'autowonder.runtime-trace.v2', source: 'OSS', dispatchId: 45, provider: 'qoder', changed: true,
      tokenUsage, events: [],
      sessions: [{
        sessionId: 's1', provider: 'qoder', status: 'COMPLETED', durationMs: 1200, tokenUsage, boundaries: [], eventIds: [], turns: [],
      }],
    };
  }

  const node = { key: 'dispatch:45', dispatchId: 45, agentName: '开发', status: 'SUCCEEDED' };

  it('shows only credits for dispatch and session usage, never token counts', async () => {
    server.use(
      http.get('/api/dispatches/45/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: tracePayload({
        available: true, inputTokens: 1700, outputTokens: 400, reasoningTokens: 80, cacheReadTokens: 400, cacheWriteTokens: 0, totalTokens: 2100, credits: 12.34,
      }) })),
    );

    render(<RuntimeTraceDrawer node={node} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    await waitFor(() => expect(within(drawer).getAllByText(/12\.34 credits/).length).toBeGreaterThan(0));
    expect(within(drawer).queryByText(/tokens/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/2,100/)).not.toBeInTheDocument();
  });

  it('renders no usage text when the trace still reports tokens without credits', async () => {
    server.use(
      http.get('/api/dispatches/45/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: tracePayload({
        available: true, inputTokens: 1700, outputTokens: 400, reasoningTokens: 80, cacheReadTokens: 400, cacheWriteTokens: 0, totalTokens: 2100,
      }) })),
    );

    render(<RuntimeTraceDrawer node={node} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    await waitFor(() => expect(within(drawer).getByText(/会话 s1/)).toBeInTheDocument());
    expect(within(drawer).queryByText(/tokens/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/credits/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/💰/)).not.toBeInTheDocument();
  });

  it('renders no usage text when the trace payload omits usage entirely', async () => {
    server.use(
      http.get('/api/dispatches/45/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: {
        schemaVersion: 'autowonder.runtime-trace.v2', source: 'OSS', dispatchId: 45, provider: 'qoder', changed: true,
        events: [],
        sessions: [{
          sessionId: 's1', provider: 'qoder', status: 'COMPLETED', durationMs: 1200, boundaries: [], eventIds: [], turns: [],
        }],
      } })),
    );

    render(<RuntimeTraceDrawer node={node} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    await waitFor(() => expect(within(drawer).getByText(/会话 s1/)).toBeInTheDocument());
    expect(within(drawer).queryByText(/credits/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/tokens/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/💰/)).not.toBeInTheDocument();
  });
});

describe('RuntimeTraceDrawer independent pane scrolling', () => {
  const liveTurn = (turnId: string, stepName: string, contextFiles: unknown[] = []) => ({
    traceId: turnId, turnId, stepName, status: 'COMPLETED', durationMs: 900, providerCoverage: 'FULL',
    tokenUsage: usage, usage, eventIds: [], spans: [], observations: [], contextFiles,
  });

  function liveTrace(dispatchId: number, turns: unknown[], events: unknown[] = []) {
    return {
      schemaVersion: 'autowonder.runtime-trace.v2', source: 'LIVE', dispatchId, provider: 'qoder', changed: true,
      tokenUsage: usage, events,
      sessions: [{ sessionId: 'session-live', provider: 'qoder', status: 'COMPLETED', durationMs: 900, tokenUsage: usage, boundaries: [], eventIds: [], turns }],
    };
  }

  // jsdom does not keep a scroll offset, so the offset is captured on the element itself.
  function spyScrollTop(pane: HTMLElement) {
    const state = { top: 0 };
    Object.defineProperty(pane, 'scrollTop', {
      configurable: true,
      get: () => state.top,
      set: (value: number) => { state.top = value; },
    });
    return state;
  }

  it('bounds the timeline and the detail into independent scroll areas', async () => {
    server.use(
      http.get('/api/dispatches/46/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: liveTrace(46, [liveTurn('t1', '需求分析')]) })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:46', dispatchId: 46, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);

    const panes = await screen.findByTestId('runtime-trace-panes');
    expect(panes).toHaveStyle({ display: 'grid', minHeight: '0', overflow: 'hidden' });
    expect(screen.getByTestId('runtime-trace-timeline-pane')).toHaveStyle({ overflow: 'auto' });
    expect(screen.getByTestId('runtime-trace-detail-pane')).toHaveStyle({ overflow: 'auto' });
    expect(document.querySelector('.ant-drawer-body')).toHaveStyle({ display: 'flex', flexDirection: 'column', minHeight: '0', overflow: 'hidden' });
  });

  it('keeps the dispatch summary and failure alert out of the shrinking panes', async () => {
    server.use(
      http.get('/api/dispatches/46/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: liveTrace(46, [], [
        { eventId: 'e-failed', eventType: 'step.failed', detail: { reason: 'gate rejected the step' } },
      ]) })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:46', dispatchId: 46, agentName: '开发', status: 'FAILED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);

    const summary = await screen.findByTestId('runtime-trace-dispatch-summary');
    expect(summary).toHaveStyle({ flexShrink: '0' });
    const alert = await screen.findByTestId('dispatch-failure-reason');
    expect(alert).toHaveStyle({ flexShrink: '0' });

    const panes = screen.getByTestId('runtime-trace-panes');
    expect(summary.contains(panes)).toBe(false);
    expect(alert.contains(panes)).toBe(false);
    expect(screen.getByTestId('runtime-trace-timeline-pane').contains(summary)).toBe(false);
  });

  it('starts the detail pane at its own top when another turn is selected', async () => {
    server.use(
      http.get('/api/dispatches/47/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: liveTrace(47, [liveTurn('t1', '需求分析'), liveTurn('t2', '编码实现')]) })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:47', dispatchId: 47, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    fireEvent.click(await within(drawer).findByText(/Turn t1/));

    const pane = within(drawer).getByTestId('runtime-trace-detail-pane');
    const scroll = spyScrollTop(pane);
    scroll.top = 320;

    fireEvent.click(within(drawer).getByText(/Turn t2/));

    expect(scroll.top).toBe(0);
    expect(within(drawer).getByTestId('runtime-trace-detail-pane')).toBe(pane);
    expect(within(drawer).getByText('编码实现')).toBeInTheDocument();
  });

  it('starts the detail pane at its own top when a context file preview opens', async () => {
    const file = { contentRef: 'ctx:1', name: 'notes.md', role: 'CONTEXT', mediaType: 'text/markdown', sizeBytes: 12, previewable: true };
    server.use(
      http.get('/api/dispatches/48/runtime-trace', () => HttpResponse.json({ success: true, code: '0', message: '', data: liveTrace(48, [liveTurn('t1', '需求分析', [file])]) })),
      http.get('/api/dispatches/48/runtime-trace/context', () => HttpResponse.text('preview body')),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:48', dispatchId: 48, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
    const drawer = await screen.findByTestId('runtime-trace-drawer');
    fireEvent.click(await within(drawer).findByText(/Turn t1/));

    const pane = within(drawer).getByTestId('runtime-trace-detail-pane');
    const scroll = spyScrollTop(pane);
    scroll.top = 260;

    fireEvent.click(within(drawer).getByText('notes.md'));

    await waitFor(() => expect(within(drawer).getByText('preview body')).toBeInTheDocument());
    expect(scroll.top).toBe(0);
  });
});

describe('RuntimeTraceDrawer states', () => {
  it('renders no trace content while the drawer is closed without a dispatch', () => {
    render(<RuntimeTraceDrawer node={null} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);

    expect(screen.queryByTestId('runtime-trace-drawer')).not.toBeInTheDocument();
    expect(screen.queryByText('执行 Trace')).not.toBeInTheDocument();
  });

  it('surfaces a trace load failure in an alert', async () => {
    server.use(
      http.get('/api/dispatches/49/runtime-trace', () => HttpResponse.json({ success: false, code: '500', message: 'trace backend exploded', data: null }, { status: 500 })),
    );

    render(<RuntimeTraceDrawer node={{ key: 'dispatch:49', dispatchId: 49, agentName: '开发', status: 'SUCCEEDED' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);

    expect(await screen.findByText('trace backend exploded')).toBeInTheDocument();
    expect(screen.queryByTestId('runtime-trace-panes')).not.toBeInTheDocument();
  });
});

describe('dispatchFailureReason', () => {
  it('returns the reason of the earliest failure event', () => {
    expect(dispatchFailureReason([
      { eventType: 'step.failed', detail: { reason: 'first failure' } },
      { eventType: 'progress', detail: { reason: 'not a failure event' } },
      { eventType: 'session.failed', detail: { reason: 'latest failure' } },
    ])).toBe('first failure');
  });

  it('ignores failure events without a usable reason', () => {
    expect(dispatchFailureReason([
      { eventType: 'step.failed', detail: { reason: '   ' } },
      { eventType: 'session.failed', detail: { reason: 123 } },
      { eventType: 'step.failed', detail: {} },
      { eventType: 'step.failed', detail: { reason: 'kept' } },
    ])).toBe('kept');
  });

  it('prefers a blocking tool hook over the symptoms recorded after it', () => {
    const blockage = 'tool_hook_blocked: kind safety_denial hook jarvis-tool-safety trigger beforeTool status blocked exitCode 2 blockedCalls 3';
    expect(dispatchFailureReason([
      { eventType: 'step.failed', detail: { reason: blockage } },
      { eventType: 'step.failed', detail: { reason: 'missing completion request' } },
      { eventType: 'session.failed', detail: { reason: 'agent turn failed' } },
    ])).toBe(blockage);
  });

  it('prefers a blocking tool hook recorded after an earlier symptom', () => {
    const blockage = 'tool_hook_blocked: kind hook_error hook jarvis-tool-safety trigger afterTool status failed exitCode 1 blockedCalls 4';
    expect(dispatchFailureReason([
      { eventType: 'step.failed', detail: { reason: 'missing completion request' } },
      { eventType: 'session.failed', detail: { reason: blockage } },
    ])).toBe(blockage);
  });

  it('does not treat an ordinary reason mentioning the hook as a blockage', () => {
    expect(dispatchFailureReason([
      { eventType: 'step.failed', detail: { reason: 'hook jarvis-tool-safety exited 1 once' } },
      { eventType: 'step.failed', detail: { reason: 'missing completion request' } },
    ])).toBe('hook jarvis-tool-safety exited 1 once');
  });

  it('returns null when there is no failure event', () => {
    expect(dispatchFailureReason([{ eventType: 'progress', detail: {} }])).toBeNull();
    expect(dispatchFailureReason([])).toBeNull();
    expect(dispatchFailureReason(null)).toBeNull();
    expect(dispatchFailureReason(undefined)).toBeNull();
  });
});

it('localizes the actual drawer close parent and hides the installed error glyph while keeping raw failure reason', async () => {
  server.use(http.get('/api/dispatches/99/runtime-trace', () => HttpResponse.json({ success: true, code: '0', data: { schemaVersion: 'autowonder.runtime-trace.v2', source: 'LIVE', dispatchId: 99, changed: true, tokenUsage: usage, events: [{ eventType: 'step.failed', detail: { reason: 'raw <b>reason</b>' } }], sessions: [] } })));
  render(<RuntimeTraceDrawer node={{ key: 'dispatch:99', dispatchId: 99, agentName: '真人', agentNameFallback: 'HUMAN', status: 'FAILED', error: 'raw <b>reason</b>' }} processGraph={{ nodes: [], edges: [] }} onClose={() => {}} />);
  await screen.findByTestId('runtime-trace-drawer');
  expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
  expect(screen.queryByRole('img', { name: 'close-circle' })).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  expect(within(screen.getByTestId('dispatch-failure-reason')).getByText('raw <b>reason</b>')).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});
