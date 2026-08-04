import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReviewConsole } from './ReviewConsole';

const items = [
  { id: 1, title: 'Memory: API patterns', subtitle: 'scope: agent-1', status: 'PENDING' as const },
  { id: 2, title: 'Agent v3 config', subtitle: 'agent: alpha', status: 'PENDING' as const },
];

describe('ReviewConsole', () => {
  it('renders review items', () => {
    render(<ReviewConsole items={items} onApprove={vi.fn()} onReject={vi.fn()} />);
    expect(screen.getByText('Memory: API patterns')).toBeInTheDocument();
    expect(screen.getByText('Agent v3 config')).toBeInTheDocument();
  });

  it('calls onApprove when clicking approve', async () => {
    const onApprove = vi.fn();
    render(<ReviewConsole items={items} onApprove={onApprove} onReject={vi.fn()} />);
    const user = userEvent.setup();
    const approveButtons = screen.getAllByRole('button', { name: /通过/ });
    await user.click(approveButtons[0]);
    expect(onApprove).toHaveBeenCalledWith(1);
  });

  it('calls onReject when clicking reject', async () => {
    const onReject = vi.fn();
    render(<ReviewConsole items={items} onApprove={vi.fn()} onReject={onReject} />);
    const user = userEvent.setup();
    const rejectButtons = screen.getAllByRole('button', { name: /驳回/ });
    await user.click(rejectButtons[0]);
    expect(onReject).toHaveBeenCalledWith(1);
  });
});
