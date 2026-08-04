import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { ResultPane } from './ResultPane';
import type { AiSession } from '@/shared/types/ai';
import { useAuthStore } from '@/shared/auth/store';

function makeSession(overrides: Partial<AiSession> = {}): AiSession {
  return {
    id: 7,
    scene: 'REPO_SCAN',
    bizRefType: 'REPO',
    bizRefId: 1,
    status: 'WAIT_USER',
    resultJson: JSON.stringify({ purpose: '订单', keyBusiness: [], upstreams: [], downstreams: [], summaryMd: '# x' }),
    error: null,
    gmtCreate: '',
    messages: [],
    ...overrides,
  };
}

describe('ResultPane', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessLevel: 'READ_WRITE' });
  });
  it('shows empty state without resultJson', () => {
    render(<ResultPane session={makeSession({ resultJson: null, status: 'QUEUED' })} />);
    expect(screen.getByText(/发起对话后这里出现结构化结果/)).toBeInTheDocument();
  });

  it('renders the scene renderer and a confirm button when WAIT_USER', () => {
    render(<ResultPane session={makeSession()} />);
    expect(screen.getByDisplayValue('订单')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /确认落库/ })).toBeInTheDocument();
  });

  it('POSTs edited struct to confirm with resultJson field', async () => {
    let body: unknown = null;
    server.use(
      http.post('/api/ai/sessions/7/confirm', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );
    const onConfirmed = vi.fn();
    render(<ResultPane session={makeSession()} onConfirmed={onConfirmed} />);
    const input = screen.getByDisplayValue('订单');
    await userEvent.type(input, '服务');
    await userEvent.click(screen.getByRole('button', { name: /确认落库/ }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
    expect(body).toMatchObject({ resultJson: expect.stringContaining('订单服务') });
  });

  it('renders read-only when COMPLETED', () => {
    render(<ResultPane session={makeSession({ status: 'COMPLETED' })} />);
    expect(screen.getByDisplayValue('订单')).toBeDisabled();
    expect(screen.queryByRole('button', { name: /确认落库/ })).not.toBeInTheDocument();
    expect(screen.getByText(/已生效/)).toBeInTheDocument();
  });

  it('uses draft wording for agent config confirmation', () => {
    render(<ResultPane session={makeSession({
      scene: 'AGENT_CONFIG_GEN',
      resultJson: JSON.stringify({
        name: 'Terraform 工单分诊助手',
        roleName: '工单分诊专员',
        roleCode: 'TERRAFORM_TRIAGE',
        businessBackground: '负责 Terraform 相关工单分诊。',
        responsibilities: '分析需求、识别负责人并输出处理建议。',
      }),
    })} />);

    expect(screen.getByRole('button', { name: /确认使用草稿/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /确认落库/ })).not.toBeInTheDocument();
  });

  it('applies agent config drafts without completing the AI session', async () => {
    const onConfirmed = vi.fn();
    let confirmCalls = 0;
    server.use(
      http.post('/api/ai/sessions/7/confirm', () => {
        confirmCalls += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    render(<ResultPane session={makeSession({
      scene: 'AGENT_CONFIG_GEN',
      resultJson: JSON.stringify({
        name: '客服助手',
        roleName: '客服专员',
        roleCode: 'CUSTOMER_SUPPORT',
        businessBackground: '负责客户咨询。',
        responsibilities: '识别问题并输出回复建议。',
      }),
    })} onConfirmed={onConfirmed} />);

    await userEvent.click(screen.getByRole('button', { name: /确认使用草稿/ }));

    expect(onConfirmed).toHaveBeenCalledWith(expect.stringContaining('CUSTOMER_SUPPORT'));
    expect(confirmCalls).toBe(0);
  });

  it('shows an error message when confirm fails', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    server.use(
      http.post('/api/ai/sessions/7/confirm', () =>
        HttpResponse.json(
          { success: false, code: '19005', message: '角色码非法', traceId: null, data: null },
          { status: 400 },
        ),
      ),
    );

    render(<ResultPane session={makeSession()} />);
    await userEvent.click(screen.getByRole('button', { name: /确认落库/ }));

    await waitFor(() => expect(errorSpy).toHaveBeenCalledWith('角色码非法'));
    errorSpy.mockRestore();
  });

  it('keeps confirm visible but does not persist for read-only members', async () => {
    useAuthStore.setState({ accessLevel: 'READ_ONLY' });
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => undefined as never);
    let confirmRequests = 0;
    server.use(
      http.post('/api/ai/sessions/7/confirm', () => {
        confirmRequests += 1;
        return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data: null });
      }),
    );

    render(<ResultPane session={makeSession()} />);
    await userEvent.click(screen.getByRole('button', { name: /确认落库/ }));

    expect(errorSpy).toHaveBeenCalledWith('当前为只读权限，确认 AI 结果落库需要读写权限');
    expect(confirmRequests).toBe(0);
    errorSpy.mockRestore();
  });
});
