import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach } from 'vitest';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { FeatureSwitchesPanel } from './FeatureSwitchesPanel';

afterEach(() => {
  message.destroy();
});

function renderPanel(canManage = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <FeatureSwitchesPanel canManage={canManage} />
    </QueryClientProvider>,
  );
}

describe('FeatureSwitchesPanel', () => {
  it('gives each switch its own named settings section', async () => {
    renderPanel();

    for (const name of ['重复工单意图检测', '用户注册审核', '默认允许用户创建空间']) {
      const section = await screen.findByRole('region', { name });
      expect(within(section).getByRole('switch', { name: `切换${name}` })).toBeInTheDocument();
      expect(within(section).getAllByRole('switch')).toHaveLength(1);
    }
  });

  it('saves each new switch with its own version', async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(
      http.put('/api/platform/feature-switches', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>;
        bodies.push(body);
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: {
            duplicateWorkitemIntentEnabled: false,
            version: 0,
            userRegistrationReviewEnabled: body.userRegistrationReviewEnabled === true,
            userRegistrationReviewVersion: 1,
            workspaceCreationDefaultAllowed: body.workspaceCreationDefaultAllowed !== false,
            workspaceCreationDefaultAllowedVersion: 1,
          },
          traceId: 'trace-feature-switches-save-new',
        });
      }),
    );

    renderPanel();

    await userEvent.click(await screen.findByRole('switch', { name: '切换用户注册审核' }));
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ userRegistrationReviewEnabled: true, userRegistrationReviewVersion: 0 });

    await userEvent.click(await screen.findByRole('switch', { name: '切换默认允许用户创建空间' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({ workspaceCreationDefaultAllowed: false, workspaceCreationDefaultAllowedVersion: 1 });
  });

  it('reverts both new switches when saving fails', async () => {
    server.use(
      http.put('/api/platform/feature-switches', () => HttpResponse.json({
        success: false,
        code: '39001',
        message: '开关配置已被他人修改，请刷新后重试',
        data: null,
        traceId: 'trace-feature-switches-conflict-new',
      })),
    );

    renderPanel();

    const review = await screen.findByRole('switch', { name: '切换用户注册审核' });
    await userEvent.click(review);
    await waitFor(() => expect(review).toHaveAttribute('aria-checked', 'false'));

    const creation = await screen.findByRole('switch', { name: '切换默认允许用户创建空间' });
    await userEvent.click(creation);
    await waitFor(() => expect(creation).toHaveAttribute('aria-checked', 'true'));
  });

  it('loads the global switch state and saves a toggle', async () => {
    let savedBody: Record<string, unknown> | null = null;
    server.use(
      http.put('/api/platform/feature-switches', async ({ request }) => {
        savedBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true,
          code: '0',
          message: '',
          data: { duplicateWorkitemIntentEnabled: true, version: 1 },
          traceId: 'trace-feature-switches-save',
        });
      }),
    );

    renderPanel();

    const toggle = await screen.findByRole('switch', { name: '切换重复工单意图检测' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    await userEvent.click(toggle);

    await waitFor(() => expect(savedBody).not.toBeNull());
    expect(savedBody).toMatchObject({ duplicateWorkitemIntentEnabled: true, expectedVersion: 0 });
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    expect(screen.getByText('已开启')).toBeInTheDocument();
  });

  it('reverts the toggle when saving fails', async () => {
    server.use(
      http.put('/api/platform/feature-switches', () => HttpResponse.json({
        success: false,
        code: '39001',
        message: '开关配置已被他人修改，请刷新后重试',
        data: null,
        traceId: 'trace-feature-switches-conflict',
      })),
    );

    renderPanel();

    const toggle = await screen.findByRole('switch', { name: '切换重复工单意图检测' });
    await userEvent.click(toggle);

    await waitFor(() => expect(screen.getByText('开关配置已被他人修改，请刷新后重试')).toBeInTheDocument());
    expect(toggle).toHaveAttribute('aria-checked', 'false');
  });

  it('disables editing for non administrators', async () => {
    renderPanel(false);

    const toggle = await screen.findByRole('switch', { name: '切换重复工单意图检测' });
    expect(toggle).toBeDisabled();
    expect(screen.getByText('仅平台管理员可以修改功能开关。')).toBeInTheDocument();
  });
});
