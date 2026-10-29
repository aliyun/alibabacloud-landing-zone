import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Modal } from 'antd';
import { ExecutionActionBar } from './ExecutionActionBar';
import type { DispatchAttempt } from '@/shared/types/workitem';
import '@/shared/i18n';

const allowed = { allowed: true };

function attempt(overrides: Partial<DispatchAttempt> = {}): DispatchAttempt {
  return {
    dispatchId: 30,
    agentName: 'RD',
    executorName: null,
    status: 'RUNNING',
    startedAt: null,
    durationMs: null,
    control: {
      pause: allowed,
      end: allowed,
      forceEnd: { allowed: false },
      resume: { allowed: false },
    },
    ...overrides,
  };
}

afterEach(() => Modal.destroyAll());

describe('ExecutionActionBar', () => {
  it('runs pause directly and confirms end before dispatching it', async () => {
    const onAction = vi.fn();
    render(<ExecutionActionBar attempt={attempt()} onAction={onAction} />);

    await userEvent.click(screen.getByRole('button', { name: '暂停' }));
    expect(onAction).toHaveBeenCalledWith('pause');

    await userEvent.click(screen.getByRole('button', { name: '更多执行操作' }));
    await userEvent.click(await screen.findByText('结束本次执行'));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByText('结束本次执行'));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('end'));
  });

  it('offers force end only from the server capability', async () => {
    const onAction = vi.fn();
    render(<ExecutionActionBar attempt={attempt({
      status: 'PAUSING',
      cancelRequested: true,
      stopPending: true,
      control: {
        pause: { allowed: false },
        end: { allowed: false },
        forceEnd: allowed,
        resume: { allowed: false },
      },
    })} onAction={onAction} />);

    expect(screen.queryByRole('button', { name: '暂停' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '强制结束' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByText('强制结束'));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('forceEnd'));
  });

  it('confirms a resume whose actual target still has an unconfirmed stop', async () => {
    const onAction = vi.fn();
    render(<ExecutionActionBar attempt={attempt({
      status: 'CANCELED',
      control: {
        pause: { allowed: false },
        end: { allowed: false },
        forceEnd: { allowed: false },
        resume: {
          allowed: true, mode: 'RETRY', targetDispatchId: 31,
          targetStatus: 'TIMEOUT', targetStopPending: true,
        },
      },
    })} onAction={onAction} />);

    await userEvent.click(screen.getByRole('button', { name: /继续|重试/ }));
    const dialog = await screen.findByRole('dialog');
    expect(onAction).not.toHaveBeenCalled();
    const buttons = within(dialog).getAllByRole('button');
    await userEvent.click(buttons[buttons.length - 1]);
    await waitFor(() => expect(onAction).toHaveBeenCalledWith('resume'));
  });

  it('shows a disabled action with the server reason when a retry is blocked by a newer execution', async () => {
    render(<ExecutionActionBar attempt={attempt({
      status: 'FAILED',
      control: {
        pause: { allowed: false },
        end: { allowed: false },
        forceEnd: { allowed: false },
        resume: { allowed: false, mode: 'RETRY', reasonKey: 'delivery.controlReason.newer', reasonArgs: ['58', '301'] },
      },
    })} onAction={vi.fn()} />);
    const retry = screen.getByRole('button', { name: '重试' });
    expect(retry).toBeDisabled();
    await userEvent.hover(retry);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('#58');
    expect(screen.getByRole('tooltip')).toHaveTextContent('301');
  });

  it('shows a disabled pause reason while a normal pause is pending', () => {
    render(<ExecutionActionBar attempt={attempt({
      status: 'PAUSING',
      control: {
        pause: { allowed: false, reasonKey: 'delivery.controlReason.pausing' },
        end: { allowed: true },
        forceEnd: { allowed: false },
        resume: { allowed: false },
      },
    })} onAction={vi.fn()} />);
    expect(screen.getByRole('button', { name: '暂停' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '结束本次执行' })).toBeEnabled();
  });
});
