import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkspaceRankCard, RANK_DISPLAY_LIMIT, type RankEntry } from './WorkspaceRankCard';

const entries: RankEntry[] = Array.from({ length: 12 }, (_, index) => ({
  id: index + 1,
  name: `空间${index + 1}`,
  value: String(index),
}));

function renderCard(props: Partial<Parameters<typeof WorkspaceRankCard>[0]> = {}) {
  const onSelect = vi.fn();
  const view = render(
    <WorkspaceRankCard
      testId="rank"
      title="已完成工单最多的空间"
      entries={entries}
      total={entries.length}
      emptyText="暂无数据"
      onSelect={onSelect}
      {...props}
    />,
  );
  return { onSelect, ...view };
}

describe('WorkspaceRankCard', () => {
  it('shows the first ten tied workspaces and summarises the rest', async () => {
    renderCard();

    expect(screen.getAllByTestId(/^rank-item-/)).toHaveLength(RANK_DISPLAY_LIMIT);
    expect(screen.queryByTestId('rank-item-11')).toBeNull();
    expect(screen.getByTestId('rank-more')).toHaveTextContent('等 12 个空间');

    await userEvent.hover(screen.getByTestId('rank-more'));
    const tooltip = await screen.findByText(/并列共 12 个空间/);
    expect(tooltip).toHaveTextContent('空间1');
    expect(tooltip).toHaveTextContent('空间12');
  });

  it('prefers the backend tie total over the rendered rows', () => {
    renderCard({ entries: entries.slice(0, 2), total: 7 });

    expect(screen.getAllByTestId(/^rank-item-/)).toHaveLength(2);
    expect(screen.getByTestId('rank-more')).toHaveTextContent('等 7 个空间');
  });

  it('drills into the workspace on click', async () => {
    const { onSelect } = renderCard();

    await userEvent.click(screen.getByTestId('rank-item-3'));

    expect(onSelect).toHaveBeenCalledWith(3);
  });

  it('renders the empty text and no summary when nothing ties', () => {
    renderCard({ entries: [], total: 0 });

    expect(screen.getByText('暂无数据')).toBeInTheDocument();
    expect(screen.queryByTestId('rank-more')).toBeNull();
  });

  it('formats each chip as name plus value', () => {
    renderCard({ entries: [{ id: 9, name: '数据中台', value: '1,234' }], total: 1 });

    const chip = screen.getByTestId('rank-item-9');
    expect(within(chip).getByText('数据中台')).toBeInTheDocument();
    expect(within(chip).getByText('1,234')).toBeInTheDocument();
  });
});
