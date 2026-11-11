import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { WorkitemContent } from './WorkitemContent';

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => (_mode: string, _label: string, action: () => void) => action(),
}));

describe('WorkitemContent duplicate checks', () => {
  it('checks the edited title and excludes the current workitem', async () => {
    let requestBody: Record<string, unknown> | null = null;
    server.use(
      http.post('/api/workitems/duplicate-checks', async ({ request }) => {
        requestBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          traceId: 'trace-dup-edit',
          data: {
            checkId: null,
            title: '被占用标题',
            exactMatches: [{
              id: 91,
              title: '被占用标题',
              statusName: '新建',
              statusCategory: 'NEW',
              url: 'https://aw.example.com/workitems/91',
              matchType: 'EXACT_TITLE',
            }],
            exactTotal: 1,
            exactTruncated: false,
            intentEnabled: false,
            intentStatus: null,
            intentMatches: [],
            intentPartial: false,
          },
        });
      }),
    );

    render(<WorkitemContent title="原标题" contentMd="正文" workitemId={55} />);

    await userEvent.click(screen.getByRole('button', { name: /编\s*辑/ }));
    const titleInput = screen.getByLabelText('工单标题');
    await userEvent.clear(titleInput);
    await userEvent.type(titleInput, '被占用标题');
    await userEvent.tab();

    await waitFor(() => expect(screen.getByText('发现重复工单')).toBeInTheDocument());
    expect(requestBody).toMatchObject({ title: '被占用标题', excludeWorkitemId: 55 });
    // 编辑态保留，提醒不影响保存。
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeInTheDocument();
  });

  it('does not check while not editing', async () => {
    let calls = 0;
    server.use(
      http.post('/api/workitems/duplicate-checks', () => {
        calls += 1;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            checkId: null, title: '', exactMatches: [], exactTotal: 0, exactTruncated: false,
            intentEnabled: false, intentStatus: null, intentMatches: [], intentPartial: false,
          },
        });
      }),
    );

    render(<WorkitemContent title="原标题" contentMd="正文" workitemId={55} />);

    await new Promise((resolve) => setTimeout(resolve, 800));
    expect(calls).toBe(0);
  });
});
