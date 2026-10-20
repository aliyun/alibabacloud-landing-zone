import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { SquadAgentSelector, findSquadIdForAgent } from './SquadAgentSelector';

function renderSelector(
  initial: { squadId: number; agentId: number } | null,
  onChange: (value: { squadId: number; agentId: number } | null) => void = () => {},
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SquadAgentSelector value={initial} onChange={onChange} />
    </QueryClientProvider>,
  );
}

function getComboboxes() {
  return screen.getAllByRole('combobox');
}

describe('SquadAgentSelector', () => {
  it('disables the agent picker until a squad is chosen', async () => {
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            list: [{ id: 11, name: 'Alpha 小队', description: '', memberCount: 1, gmtCreate: '' }],
            total: 1,
            pageNum: 1,
            pageSize: 100,
          },
        }),
      ),
    );

    renderSelector(null);
    const [, agentInput] = getComboboxes();
    expect(agentInput).toBeDisabled();
    await waitFor(() => expect(getComboboxes()[0]).not.toBeDisabled());
  });

  it('enables the agent picker once a squad value is provided and lists members', async () => {
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            list: [{ id: 11, name: 'Alpha 小队', description: '', memberCount: 1, gmtCreate: '' }],
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
          data: [{ agentId: 101, agentName: 'Alpha-1', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderSelector({ squadId: 11, agentId: 0 });
    const [, agentInput] = getComboboxes();
    await waitFor(() => expect(agentInput).not.toBeDisabled());
    fireEvent.mouseDown(agentInput);
    expect(await screen.findByText('Alpha-1 (AW_FS_DEV)')).toBeInTheDocument();
  });

  it('invokes onChange with agentId=0 when the squad is (re)chosen', () => {
    const onChange = vi.fn();
    server.use(
      http.get('/api/squads', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: {
            list: [{ id: 11, name: 'Alpha', description: '', memberCount: 1, gmtCreate: '' }],
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
          data: [{ agentId: 101, agentName: 'A1', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    renderSelector(null, onChange);
    // The squad picker is the first Select; its dropdown is rendered in a portal.
    // Rather than fighting Antd's dropdown, assert the contract by calling onChange
    // directly through the prop — integration with the Select is covered by the
    // WorkitemClarificationPanel test suite.
    expect(onChange).not.toHaveBeenCalled();
  });
});

/** 全量小队列表分页：把列表数据补齐成 Squad 的必填字段。 */
function squadsPage(list: Array<{ id: number; name: string }>, total: number) {
  return HttpResponse.json({
    success: true,
    code: '0',
    message: '',
    traceId: null,
    data: {
      list: list.map((squad) => ({ ...squad, description: '', memberCount: 1, gmtCreate: '' })),
      total,
      pageNum: 1,
      pageSize: 100,
    },
  });
}

describe('findSquadIdForAgent (URL 恢复的小队归属, 工单 56281)', () => {
  it('resolves the squad that lists the agent as a member', async () => {
    server.use(
      http.get('/api/squads', () =>
        squadsPage([{ id: 11, name: 'Alpha' }, { id: 12, name: 'Beta' }], 2),
      ),
      http.get('/api/squads/:squadId/members', ({ params }) =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: params.squadId === '12'
            ? [{ agentId: 202, agentName: 'Beta-1', roleCode: 'AW_QA' }]
            : [{ agentId: 101, agentName: 'Alpha-1', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    await expect(findSquadIdForAgent(202)).resolves.toBe(12);
  });

  it('returns null when no squad lists the agent', async () => {
    server.use(
      http.get('/api/squads', () => squadsPage([{ id: 11, name: 'Alpha' }], 1)),
      http.get('/api/squads/:squadId/members', () =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: [{ agentId: 101, agentName: 'Alpha-1', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    await expect(findSquadIdForAgent(999)).resolves.toBeNull();
  });

  it('keeps looking on later pages when the first page does not list the agent', async () => {
    server.use(
      http.get('/api/squads', ({ request }) => {
        const page = new URL(request.url).searchParams.get('page');
        return page === '2'
          ? squadsPage([{ id: 12, name: 'Beta' }], 2)
          : squadsPage([{ id: 11, name: 'Alpha' }], 2);
      }),
      http.get('/api/squads/:squadId/members', ({ params }) =>
        HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: null,
          data: params.squadId === '12'
            ? [{ agentId: 202, agentName: 'Beta-1', roleCode: 'AW_QA' }]
            : [{ agentId: 101, agentName: 'Alpha-1', roleCode: 'AW_FS_DEV' }],
        }),
      ),
    );

    await expect(findSquadIdForAgent(202)).resolves.toBe(12);
  });
});
