import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { StartDeliveryModal } from './StartDeliveryModal';
import { writeClarificationPrefill } from '../clarification/prefill';

function renderModal(hasSdlc = false, workitemId: number | string = 10000) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StartDeliveryModal
        open
        workitemId={workitemId}
        hasSdlc={hasSdlc}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe('StartDeliveryModal', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('loads squads instead of online agents and hides SDLC selection', async () => {
    const requestedAgentUrls: string[] = [];
    let squadsRequested = false;

    server.use(
      http.get('/api/squads', () => {
        squadsRequested = true;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 2, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        });
      }),
      http.get('/api/agents', ({ request }) => {
        requestedAgentUrls.push(request.url);
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: [] });
      }),
    );

    renderModal();

    expect(screen.queryByLabelText('SDLC 流程')).not.toBeInTheDocument();
    expect(await screen.findByLabelText('小队')).toBeInTheDocument();
    expect(screen.getByLabelText('首步执行 Agent')).toBeInTheDocument();
    await waitFor(() => expect(squadsRequested).toBe(true));
    expect(requestedAgentUrls.some((url) => url.includes('status=ONLINE'))).toBe(false);
  });

  it('prefills from clarification localStorage for a fresh delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(false, '10000');
    expect(await screen.findByText('交付小队')).toBeInTheDocument();
  });

  it('does not prefill when reassigning an existing delivery', async () => {
    writeClarificationPrefill('10000', { squadId: 1, agentId: 77 });
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: { list: [{ id: 1, name: '交付小队', description: '', memberCount: 1, gmtCreate: '' }], total: 1, pageNum: 1, pageSize: 100 },
        }),
      ),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 77, agentName: 'Agent-77', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderModal(true, '10000');
    // Wait for squads to finish loading; no squad label should appear because
    // the re-assign flow skips the clarification prefill.
    await waitFor(() => expect(screen.getByLabelText('小队')).toBeInTheDocument());
    expect(screen.queryByText('交付小队')).not.toBeInTheDocument();
  });
});
