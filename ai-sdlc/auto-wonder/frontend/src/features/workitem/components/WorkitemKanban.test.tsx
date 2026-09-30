import { ConfigProvider } from 'antd';
import { createAppearanceTheme } from '@/shared/theme/appearance';
import { statusCategoryOf } from '../constants';
import { describe, it, expect, vi } from 'vitest';
import { within, render, screen, fireEvent } from '@testing-library/react';
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
    statusCategory: 'NEW',
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
      mk({ id: 1, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 10, assigneeDisplayName: '张三', title: '决策A' }),
      mk({ id: 2, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 10, assigneeName: '张三', title: '决策B' }),
      mk({ id: 3, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 20, assigneeDisplayName: '李四', title: '决策C' }),
    ];
    renderKanban({ items, pendingDecisionSummary: { name: '张三', count: 52 } });

    // 三个待决策工单标题都在
    expect(screen.getByText('决策A')).toBeInTheDocument();
    expect(screen.getByText('决策B')).toBeInTheDocument();
    expect(screen.getByText('决策C')).toBeInTheDocument();
    const summary = screen.getByLabelText('决策人汇总');
    expect(summary.parentElement).toContainElement(screen.getByText('待决策'));
    expect(within(summary).getByText('张三')).toBeInTheDocument();
    expect(within(summary).queryByText('李四')).not.toBeInTheDocument();
    expect(within(summary).getByTitle('52')).toBeInTheDocument();
    expect(summary).toHaveStyle({ marginLeft: 'auto' });
  });

  it('filters to only mine when onlyMine is set', () => {
    const items = [
      mk({ id: 1, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 42, assigneeDisplayName: '张三', title: '我的决策' }),
      mk({ id: 2, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 99, assigneeDisplayName: '李四', title: '别人的决策' }),
    ];
    renderKanban({ items, onlyMine: true, currentUserId: 42, pendingDecisionSummary: { name: '张三', count: 1 } });
    expect(screen.queryByLabelText('决策人汇总')).not.toBeInTheDocument();

    expect(screen.getByText('我的决策')).toBeInTheDocument();
    expect(screen.queryByText('别人的决策')).not.toBeInTheDocument();
    // 别的决策人不应出现
    expect(screen.queryByText('李四')).not.toBeInTheDocument();
  });

  it('places a single decision owner beside the column title without a duplicate group heading', () => {
    const name = '需要完整悬停展示的超长决策人姓名';
    renderKanban({ pendingDecisionSummary: { name, count: 1 }, items: [mk({ id: 1, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 42, assigneeDisplayName: name })] });
    const owner = screen.getByTitle(name);
    expect(screen.getByLabelText('决策人汇总').parentElement).toContainElement(screen.getByText('待决策'));
    expect(within(owner.parentElement!).getByTitle('1')).toBeInTheDocument();
    expect(screen.getAllByText(name)).toHaveLength(1);
  });

  it('shows dedicated empty hint when onlyMine yields nothing', () => {
    const items = [
      mk({ id: 1, statusCategory: 'PENDING_DECISION', assigneeType: 'HUMAN', assigneeRef: 99, assigneeDisplayName: '李四', title: '别人的决策' }),
    ];
    renderKanban({ items, onlyMine: true, currentUserId: 42, pendingDecisionSummary: { name: '张三', count: 1 } });
    expect(screen.queryByLabelText('决策人汇总')).not.toBeInTheDocument();

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

  it('labels imported workitems and shows the external reporter', () => {
    renderKanban({ items: [mk({
      id: 10,
      title: '来源需求',
      statusName: '待处理',
      sourceType: 'EXTERNAL',
      sourceProvider: 'AONE',
      sourceUrl: 'https://aone.example.com/v2/project/2087214/req/84877007',
      creatorDisplayName: '导入人（10009）',
      sourceCreator: {
        id: 20001, provider: 'AONE', subjectId: '440501', subjectType: 'USER',
        displayName: '煊童', mappedUserId: null,
      },
    })] });

    const sourceLink = screen.getByRole('link', { name: /来自 Aone/ });
    expect(sourceLink).toHaveAttribute(
      'href',
      'https://aone.example.com/v2/project/2087214/req/84877007',
    );
    expect(sourceLink).toHaveAttribute('target', '_blank');
    expect(screen.getByText('来源提出人: 煊童')).toBeInTheDocument();
    expect(screen.queryByText(/440501/)).not.toBeInTheDocument();
    expect(screen.queryByText('创建者: 导入人（10009）')).not.toBeInTheDocument();
  });

  it('does not add a source tag to locally created workitems', () => {
    renderKanban({ items: [mk({ id: 11, title: '本地创建', sourceType: 'NATIVE' })] });

    expect(screen.queryByText(/来自 Aone/)).not.toBeInTheDocument();
  });
});

describe('WorkitemKanban 优先级与姓名展示', () => {
  it('renders the shared Chinese priority label with its color', () => {
    renderKanban({ items: [mk({ id: 1, priority: 0, title: '紧急工单' })] });
    expect(screen.getByText('紧急')).toHaveClass('ant-tag-red');
  });

  it('shows 未知优先级 for unknown priority values without treating them as low', () => {
    renderKanban({ items: [mk({ id: 1, priority: 7, title: '未知优先级工单' })] });
    expect(screen.getByText('未知优先级')).toBeInTheDocument();
  });

  it('strips id suffixes from assignee and creator names', () => {
    renderKanban({ items: [mk({
      id: 1,
      title: '姓名工单',
      assigneeType: 'AGENT',
      assigneeRef: 40013,
      assigneeName: 'agent-40013',
      assigneeDisplayName: 'AW全栈开发(40013)',
      creatorDisplayName: '蔡何（10000）',
    })] });

    expect(screen.getByText('当前处理人: AW全栈开发')).toBeInTheDocument();
    expect(screen.getByText('创建者: 蔡何')).toBeInTheDocument();
    expect(screen.queryByText(/40013/)).not.toBeInTheDocument();
    expect(screen.queryByText(/10000/)).not.toBeInTheDocument();
  });

  it('preserves full long metadata in hover titles and renders the assignee as text', () => {
    const creator = '负责前端验收的超长创建者名称';
    const assignee = '负责前端验收的超长数字员工名称';
    const status = '等待人工确认的超长状态名称';
    renderKanban({ items: [mk({ id: 1, creatorDisplayName: creator, assigneeType: 'AGENT', assigneeDisplayName: assignee, statusName: status })] });
    const fields = [screen.getByTitle(`创建者: ${creator}`), screen.getByTitle(`当前处理人: ${assignee}`), screen.getByTitle(`状态: ${status}`)];
    expect(fields[1]).toHaveTextContent(`当前处理人: ${assignee}`);
    expect(fields[1].querySelector('svg')).toBeNull();
    fields.slice(0, 2).forEach(field => expect(field).toHaveClass('ant-typography-ellipsis'));
    expect(fields[2]).toHaveClass('ant-tag');
    expect(fields[2]).toHaveStyle({ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' });
    expect(fields[1].parentElement).toBe(fields[0].parentElement);
    expect(fields[2].parentElement).not.toBe(fields[0].parentElement);
    expect(fields[2].parentElement).toContainElement(screen.getByText('需求'));
    expect(fields[2].parentElement).toContainElement(screen.getByText('低'));
  });

  it('shows 未指派 for unassigned cards', () => {
    renderKanban({ items: [mk({ id: 1, title: '无人工单', assigneeName: null, assigneeDisplayName: null })] });
    expect(screen.getByText('当前处理人: 未指派')).toBeInTheDocument();
  });

  it('falls back to 未返回 for external cards without a reporter', () => {
    renderKanban({ items: [mk({ id: 1, title: '无提出人工单', sourceType: 'EXTERNAL', sourceCreator: undefined })] });
    expect(screen.getByText('来源提出人: 未返回')).toBeInTheDocument();
  });
});

describe('WorkitemKanban 人工处理与异常标记', () => {
  it('shows the assignee without a duplicate human-intervention tag', () => {
    const items = [
      mk({ id: 1, statusCategory: 'PENDING_DECISION', statusName: '开发中', assigneeType: 'HUMAN', assigneeRef: 10000, assigneeDisplayName: '蔡何', title: '人工工单' }),
    ];
    renderKanban({ items });

    expect(screen.queryByText(/需人工（蔡何）/)).not.toBeInTheDocument();
    expect(screen.getByText('当前处理人: 蔡何')).toBeInTheDocument();
  });

  it('does not show 需人工 tag for agent-assigned pending-decision cards', () => {
    const items = [
      mk({ id: 1, statusCategory: 'PENDING_DECISION', statusName: '开发中', assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01', title: '机器工单' }),
    ];
    renderKanban({ items });

    expect(screen.getByText('机器工单')).toBeInTheDocument();
    expect(screen.queryByText(/需人工/)).not.toBeInTheDocument();
  });

  it('does not show 需人工 tag when no human is explicitly assigned', () => {
    const items = [
      mk({ id: 1, statusCategory: 'PENDING_DECISION', statusName: '待处理', assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, title: '未指派工单' }),
    ];
    renderKanban({ items });

    expect(screen.getByText('未指派工单')).toBeInTheDocument();
    expect(screen.queryByText(/需人工/)).not.toBeInTheDocument();
  });

  it('places the abnormal tag in the priority row without a duplicate assignee', () => {
    const items = [
      mk({
        id: 1, statusCategory: 'PENDING_DECISION', statusName: '开发中', assigneeType: 'HUMAN', assigneeRef: 10000, assigneeDisplayName: '蔡何',
        health: 'STUCK', healthReason: '执行超时', title: '又卡又需人工',
      }),
    ];
    renderKanban({ items });

    expect(screen.queryByText(/需人工（蔡何）/)).not.toBeInTheDocument();
    expect(screen.getByText('当前处理人: 蔡何')).toBeInTheDocument();
    const statusRow = screen.getByTitle('状态: 开发中').parentElement!;
    expect(statusRow).toContainElement(screen.getByText('异常'));
    expect(statusRow).toContainElement(screen.getByText('低'));
    expect(statusRow).toHaveStyle({ flexWrap: 'wrap' });
  });

  it('does not show 需人工 tag merely because a human is assigned outside pending decision', () => {
    const items = [
      mk({
        id: 1, statusCategory: 'NEW', statusName: '待决策复核', assigneeType: 'HUMAN',
        assigneeRef: 10000, assigneeDisplayName: '蔡何', title: '新建人工工单',
      }),
    ];
    renderKanban({ items });

    expect(screen.getByText('新建人工工单')).toBeInTheDocument();
    expect(screen.queryByText(/需人工/)).not.toBeInTheDocument();
  });
});

describe('WorkitemKanban 定时执行标识', () => {
  it('shows 定时执行 icon only for scheduled workitems', () => {
    const items = [
      mk({ id: 1, statusName: '待处理', assigneeType: 'AGENT', assigneeRef: 5, title: '定时工单', scheduledStartAt: '2026-09-01T02:00:00Z' }),
      mk({ id: 2, statusName: '待处理', assigneeType: 'HUMAN', assigneeRef: null, title: '普通工单' }),
    ];
    renderKanban({ items });

    expect(screen.getByText('定时工单')).toBeInTheDocument();
    expect(screen.getByText('普通工单')).toBeInTheDocument();
    expect(screen.getByLabelText('定时执行')).toBeInTheDocument();
  });

  it('renders no 定时执行 icon when no workitem is scheduled', () => {
    renderKanban({ items: [mk({ id: 1, statusName: '待处理', title: '普通工单' })] });

    expect(screen.getByText('普通工单')).toBeInTheDocument();
    expect(screen.queryByLabelText('定时执行')).not.toBeInTheDocument();
  });
});


describe('WorkitemKanban 拖拽', () => {
  function drag(id: number, column: string) {
    const dataTransfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' };
    fireEvent.dragStart(screen.getByTestId(`workitem-card-${id}`), { dataTransfer });
    fireEvent.dragOver(screen.getByTestId(`kanban-column-${column}`), { dataTransfer });
    fireEvent.drop(screen.getByTestId(`kanban-column-${column}`), { dataTransfer });
  }

  it('requests a move without optimistically relocating the card', () => {
    const item = mk({ id: 1, statusName: '待处理' });
    const onMove = vi.fn();
    renderKanban({ items: [item], onMove });
    drag(1, 'IN_PROGRESS');
    expect(onMove).toHaveBeenCalledWith(item, 'IN_PROGRESS');
    expect(screen.getByTestId('kanban-column-NEW')).toContainElement(screen.getByTestId('workitem-card-1'));
    fireEvent.drop(screen.getByTestId('kanban-column-DONE'));
    expect(onMove).toHaveBeenCalledTimes(1);
  });

  it('ignores same-column and external drops', () => {
    const onMove = vi.fn();
    renderKanban({ items: [mk({ id: 1, statusName: '待处理' })], onMove });
    drag(1, 'NEW');
    fireEvent.drop(screen.getByTestId('kanban-column-DONE'));
    expect(onMove).not.toHaveBeenCalled();
  });

  it('supports cards grouped under a decision maker', () => {
    const item = mk({ id: 1, statusCategory: 'PENDING_DECISION' });
    const onMove = vi.fn();
    renderKanban({ items: [item], onMove });
    drag(1, 'DONE');
    expect(onMove).toHaveBeenCalledWith(item, 'DONE');
  });

  it('disables dragging while a transition is being checked or saved', () => {
    const onMove = vi.fn();
    renderKanban({ items: [mk({ id: 1, statusName: '待处理' })], onMove, transitionBusy: true });
    expect(screen.getByTestId('workitem-card-1')).toHaveAttribute('draggable', 'false');
    drag(1, 'DONE');
    expect(onMove).not.toHaveBeenCalled();
  });
});

describe('WorkitemKanban counts', () => {
  it('shows full server totals above 99 even when only part of the column is loaded', () => {
    const { container } = renderKanban({
      items: [], columnKeys: ['NEW'], columnTotals: { NEW: 12345 },
    });
    expect(container.querySelector('.ant-badge-count')).toHaveTextContent('12345');
    expect(screen.queryByText('99+')).not.toBeInTheDocument();
  });
});


describe('WorkitemKanban 列归属（规格 3.1）', () => {
  it('groups cards strictly by the server-provided statusCategory, never by status name', () => {
    const items = [
      mk({ id: 1, title: '执行卡片', statusName: '待处理', statusCategory: 'IN_PROGRESS' }),
      mk({ id: 2, title: '名称像执行的新卡片', statusName: '开发中', statusCategory: 'NEW' }),
    ];
    renderKanban({ items });

    expect(screen.getByTestId('kanban-column-IN_PROGRESS')).toContainElement(screen.getByTestId('workitem-card-1'));
    expect(screen.getByTestId('kanban-column-NEW')).toContainElement(screen.getByTestId('workitem-card-2'));
  });

  it('hides CANCELED workitems from the board（优先级 0：看板不展示）', () => {
    renderKanban({ items: [mk({ id: 1, title: '已取消工单', statusCategory: 'CANCELED' })] });
    expect(screen.queryByText('已取消工单')).not.toBeInTheDocument();
    expect(screen.queryByTestId('workitem-card-1')).not.toBeInTheDocument();
  });

  it('falls back to the NEW column when the server category is missing', () => {
    renderKanban({ items: [mk({ id: 1, title: '兜底卡片', statusCategory: null })] });
    expect(screen.getByTestId('kanban-column-NEW')).toContainElement(screen.getByTestId('workitem-card-1'));
  });
});

describe('WorkitemKanban 排队标记', () => {
  it('shows pending execution in the original in-progress column and clears it after dispatch', () => {
    const item = mk({ id: 91, title: '等待执行器', statusName: '开发中', statusCategory: 'IN_PROGRESS', executionStatus: 'PENDING' });
    const { rerender } = renderKanban({ items: [item] });
    const card = screen.getByTestId('workitem-card-91');
    expect(within(card).getByText('排队中')).toBeInTheDocument();
    expect(within(card).getByText('开发中')).toBeInTheDocument();
    expect(statusCategoryOf(item)).toBe('IN_PROGRESS');
    expect(screen.getByTestId('kanban-column-IN_PROGRESS')).toContainElement(card);
    rerender(<MemoryRouter><WorkitemKanban items={[{ ...item, executionStatus: 'RUNNING' }]} /></MemoryRouter>);
    expect(screen.queryByText('排队中')).not.toBeInTheDocument();
  });

  it('does not mark completed workitems as queued from an old dispatch', () => {
    renderKanban({ items: [mk({ statusName: '已完成', statusCategory: 'DONE', executionStatus: 'PENDING' })] });
    expect(screen.queryByText('排队中')).not.toBeInTheDocument();
  });
});

it.each(['dark', 'light'] as const)('uses the %s theme surface for kanban columns', (mode) => {
  const config = createAppearanceTheme(mode, '#f97316');
  render(<ConfigProvider theme={config}><MemoryRouter><WorkitemKanban items={[]} /></MemoryRouter></ConfigProvider>);
  expect(screen.getByTestId('kanban-column-NEW')).toHaveStyle({background: config.token!.colorFillAlter});
});
