import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { WorkitemKanban } from './WorkitemKanban';
import type { Workitem } from '@/shared/types/workitem';

function mk(partial: Partial<Workitem>): Workitem {
  return {
    id: 1,
    workType: 'REQ',
    title: 't',
    contentMd: '',
    templateId: null,
    statusNodeId: null,
    statusName: '开发中',
    sdlcId: null,
    sdlcName: null,
    assigneeType: 'HUMAN',
    assigneeRef: null,
    assigneeName: null,
    assigneeDisplayName: null,
    creatorId: null,
    creatorName: null,
    creatorDisplayName: null,
    priority: 3,
    version: 1,
    gmtCreate: '',
    gmtModified: '',
    ...partial,
  } as Workitem;
}

function renderKanban(props: React.ComponentProps<typeof WorkitemKanban>) {
  return render(
    <MemoryRouter>
      <WorkitemKanban {...props} />
    </MemoryRouter>,
  );
}

describe('WorkitemKanban 待决策按人分类', () => {
  it('groups pending-decision column by assignee', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '张三', title: '决策A' }),
      mk({ id: 2, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 10, assigneeName: '张三', title: '决策B' }),
      mk({ id: 3, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 20, assigneeDisplayName: '李四', title: '决策C' }),
    ];
    renderKanban({ items });

    // 三个待决策工单标题都在
    expect(screen.getByText('决策A')).toBeInTheDocument();
    expect(screen.getByText('决策B')).toBeInTheDocument();
    expect(screen.getByText('决策C')).toBeInTheDocument();
    // 两个决策人都出现（组头 + 卡片底部各出现）
    expect(screen.getAllByText('张三').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('李四').length).toBeGreaterThanOrEqual(1);
  });

  it('filters to only mine when onlyMine is set', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 42, assigneeDisplayName: '张三', title: '我的决策' }),
      mk({ id: 2, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 99, assigneeDisplayName: '李四', title: '别人的决策' }),
    ];
    renderKanban({ items, onlyMine: true, currentUserId: 42 });

    expect(screen.getByText('我的决策')).toBeInTheDocument();
    expect(screen.queryByText('别人的决策')).not.toBeInTheDocument();
    // 别的决策人不应出现
    expect(screen.queryByText('李四')).not.toBeInTheDocument();
  });

  it('shows dedicated empty hint when onlyMine yields nothing', () => {
    const items = [
      mk({ id: 1, pendingDecision: true, assigneeType: 'HUMAN', assigneeRef: 99, assigneeDisplayName: '李四', title: '别人的决策' }),
    ];
    renderKanban({ items, onlyMine: true, currentUserId: 42 });

    expect(screen.getByText('暂无需要您决策的工单')).toBeInTheDocument();
    expect(screen.queryByText('别人的决策')).not.toBeInTheDocument();
  });

  it('leaves non-pending columns flat (no grouping headers)', () => {
    const items = [
      mk({ id: 1, pendingDecision: false, statusName: '待处理', assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '张三', title: '普通任务' }),
    ];
    renderKanban({ items });

    expect(screen.getByText('普通任务')).toBeInTheDocument();
  });
});
