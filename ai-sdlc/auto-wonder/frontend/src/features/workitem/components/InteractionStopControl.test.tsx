import { Modal } from 'antd';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InteractionStopControl } from './InteractionStopControl';
import type { CommentInteraction } from '@/shared/types/workitem';

const interaction: CommentInteraction = {
  guidanceId: 9,
  dispatchId: 91,
  targetAgentId: 40013,
  targetAgentName: 'AW全栈开发',
  status: 'DELIVERED',
  executionStatus: 'RUNNING',
  controlEligible: true,
  control: { end: { allowed: true }, forceEnd: { allowed: false } },
};

function renderControl(overrides: Partial<CommentInteraction> = {}, props: Partial<Parameters<typeof InteractionStopControl>[0]> = {}) {
  const onAction = vi.fn().mockResolvedValue(undefined);
  const onRefresh = vi.fn();
  const view = render(<InteractionStopControl interaction={{ ...interaction, ...overrides }} onAction={onAction} onRefresh={onRefresh} {...props} />);
  return { onAction, onRefresh, container: view.container };
}

describe('comment interaction stop control', () => {
  afterEach(() => { Modal.destroyAll(); cleanup(); });

  it('renders an icon-only end control with an accessible name', () => {
    const { container } = renderControl();

    const button = within(container).getByRole('button', { name: '结束本次执行' });
    expect(button.textContent).toBe('');
    expect(within(container).queryByRole('button', { name: '强制结束' })).not.toBeInTheDocument();
    expect(within(container).queryByRole('button', { name: /暂停|继续/ })).not.toBeInTheDocument();
  });

  it('confirms with the execution id and only sends the request on confirm', async () => {
    const { onAction, container } = renderControl();

    await userEvent.click(within(container).getByRole('button', { name: '结束本次执行' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('结束执行 #91？').length).toBeGreaterThan(0);
    expect(dialog.textContent).not.toContain('AW全栈开发');
    expect(dialog.textContent).not.toContain('评论 #');

    // antd 在两个汉字的按钮文案中插入空格，无障碍名称因此是「返 回」。
    await userEvent.click(within(dialog).getByRole('button', { name: /返\s?回/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onAction).not.toHaveBeenCalled();

    await userEvent.click(within(container).getByRole('button', { name: '结束本次执行' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '结束本次执行' }));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('end'));
  });

  it('offers force end once the cancel request is pending', async () => {
    const { onAction, container } = renderControl({
      status: 'DELIVERED',
      executionStatus: 'PAUSING',
      cancelRequested: true,
      control: { end: { allowed: false, reasonKey: 'delivery.controlReason.cancelling' }, forceEnd: { allowed: true } },
    });

    await userEvent.click(within(container).getByRole('button', { name: '强制结束' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('强制结束执行 #91？').length).toBeGreaterThan(0);
    await userEvent.click(within(dialog).getByRole('button', { name: '强制结束' }));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('forceEnd'));
  });

  it('stays hidden without a dispatch binding or a server capability', () => {
    const withoutDispatch = renderControl({ dispatchId: undefined });
    expect(within(withoutDispatch.container).queryByRole('button')).not.toBeInTheDocument();
    cleanup();
    const withoutCapability = renderControl({ control: { end: { allowed: false }, forceEnd: { allowed: false } } });
    expect(within(withoutCapability.container).queryByRole('button')).not.toBeInTheDocument();
  });

  it('marks the pending action as busy', () => {
    const { container } = renderControl({}, { pending: 'end' });
    expect(within(container).getByRole('button', { name: '结束本次执行' }).className).toContain('ant-btn-loading');
  });

  it('reports a failed request locally and refreshes the card on demand', async () => {
    const onAction = vi.fn().mockRejectedValue(new Error('执行状态已变化'));
    const onRefresh = vi.fn();
    const { container } = render(<InteractionStopControl interaction={interaction} onAction={onAction} onRefresh={onRefresh} />);

    await userEvent.click(within(container).getByRole('button', { name: '结束本次执行' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '结束本次执行' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('结束请求失败');
    await userEvent.click(within(container).getByRole('button', { name: '刷新状态' }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });
});
