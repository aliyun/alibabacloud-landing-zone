import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PlanCard } from './PlanCard';

describe('PlanCard', () => {
  // F6：三态图标是用户判断进度的唯一依据。
  it('renders each entry with its status affordance', () => {
    const view = render(
      <PlanCard
        entries={[
          { content: '已完成项', priority: 'medium', status: 'completed' },
          { content: '进行中项', priority: 'high', status: 'in_progress' },
          { content: '待办项', priority: 'low', status: 'pending' },
        ]}
      />,
    );

    const completed = screen.getByTestId('plan-entry-completed-0');
    const running = screen.getByTestId('plan-entry-in_progress-1');
    const pending = screen.getByTestId('plan-entry-pending-2');

    expect(completed).toBeTruthy();
    expect(running).toBeTruthy();
    expect(pending).toBeTruthy();
    expect(screen.getByText('已完成项')).toBeInTheDocument();
    expect(screen.getByText('进行中项')).toBeInTheDocument();
    expect(screen.getByText('待办项')).toBeInTheDocument();

    // 勾 / spinner / 空心圈 三种可视差异
    expect(completed.querySelector('.anticon-check-circle')).not.toBeNull();
    expect(running.querySelector('.ant-spin')).not.toBeNull();
    expect(pending.querySelector('[data-testid="plan-entry-icon-pending"]')).not.toBeNull();
    expect(view.container.textContent).toContain('执行计划');
  });

  it('marks completed entries with a struck-through style so scanning the list is instant', () => {
    render(<PlanCard entries={[{ content: '已完成项', priority: 'low', status: 'completed' }]} />);
    expect(screen.getByText('已完成项')).toHaveStyle({ textDecoration: 'line-through' });
  });

  it('renders nothing for an empty plan', () => {
    const { container } = render(<PlanCard entries={[]} />);
    expect(container.querySelector('[data-testid^="plan-entry"]')).toBeNull();
    expect(container.textContent).toBe('');
  });
});


it('preserves unknown external plan priorities instead of rendering a missing translation', () => {
  render(<PlanCard entries={[{ content: 'Provider plan', priority: 'provider_custom' as never, status: 'pending' }]} />);
  expect(screen.getByText('provider_custom')).toBeInTheDocument();
});
