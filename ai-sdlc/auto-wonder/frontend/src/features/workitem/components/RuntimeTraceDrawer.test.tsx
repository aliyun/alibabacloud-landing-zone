import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { RuntimeTraceDrawer } from './RuntimeTraceDrawer';

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
    expect(within(drawer).getByText(/partial coverage/)).toBeInTheDocument();
    expect(within(drawer).getByText('Observed Thinking')).toBeInTheDocument();
    expect(within(drawer).getByText('Model / Provider Wait')).toBeInTheDocument();

    fireEvent.click(within(drawer).getByText('code.search'));
    await waitFor(() => expect(observationRequests).toBe(1));
    expect(within(drawer).getByText(/posterior/)).toBeInTheDocument();
    expect(within(drawer).getByText('exact tool result')).toBeInTheDocument();
  });
});
