import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkitemMeta } from './WorkitemMeta';

const base = {
  workType: 'REQ',
  statusName: '开发中',
  priority: 1,
  assigneeName: 'caihe',
  assigneeDisplayName: '蔡何(10000)',
  assigneeType: 'HUMAN',
  creatorDisplayName: '淘飞(10018)',
  sdlcName: '标准流程',
};

describe('WorkitemMeta', () => {
  it('groups type, status, priority, creator and assignee in order', () => {
    render(<WorkitemMeta {...base} />);
    const type = screen.getByText('需求').parentElement!;
    const row = type.parentElement!;
    expect(Array.from(row.children).slice(0, 5).map((item) => item.textContent)).toEqual([
      '工单类型: 需求', '工单状态: 开发中', '优先级: 高', '创建者: 淘飞', '当前处理人: 蔡何',
    ]);
  });

  it('labels the assignee as 当前处理人 and strips id suffixes from names', () => {
    render(<WorkitemMeta {...base} />);
    expect(screen.getByText((_, node) => node?.textContent === '当前处理人: 蔡何')).toBeInTheDocument();
    expect(screen.getByText((_, node) => node?.textContent === '创建者: 淘飞')).toBeInTheDocument();
    expect(screen.queryByText(/10000/)).not.toBeInTheDocument();
    expect(screen.queryByText(/10018/)).not.toBeInTheDocument();
    expect(screen.queryByText(/指派:/)).not.toBeInTheDocument();
  });

  it('marks agent assignees with the AI tag and keeps their name', () => {
    render(
      <WorkitemMeta
        {...base}
        assigneeType="AGENT"
        assigneeName="agent-40013"
        assigneeDisplayName="AW全栈开发(40013)"
      />,
    );
    expect(screen.getByText('AI')).toBeInTheDocument();
    expect(screen.getByText(/AW全栈开发/)).toBeInTheDocument();
    expect(screen.queryByText(/40013/)).not.toBeInTheDocument();
  });

  it('shows 未指派 when nobody is assigned', () => {
    render(<WorkitemMeta {...base} assigneeName={null} assigneeDisplayName={null} />);
    expect(screen.getByText((_, node) => node?.textContent === '当前处理人: 未指派')).toBeInTheDocument();
  });

  it('falls back to the login name when no display name exists', () => {
    render(<WorkitemMeta {...base} assigneeDisplayName={null} />);
    expect(screen.getByText((_, node) => node?.textContent === '当前处理人: caihe')).toBeInTheDocument();
  });

  it('renders the Chinese priority label from the shared mapping', () => {
    render(<WorkitemMeta {...base} priority={0} />);
    expect(screen.getByText('紧急')).toHaveClass('ant-tag-red');
  });

  it('shows 未知优先级 for unknown priority values', () => {
    render(<WorkitemMeta {...base} priority={9} />);
    expect(screen.getByText('未知优先级')).toBeInTheDocument();
  });

  it('hides optional meta lines when absent', () => {
    render(
      <WorkitemMeta
        workType="REQ"
        statusName={null}
        priority={2}
        assigneeName={null}
        assigneeType="HUMAN"
        creatorDisplayName={undefined}
        sdlcName={null}
      />,
    );
    expect(screen.queryByText(/SDLC/)).not.toBeInTheDocument();
    expect(screen.queryByText(/创建者/)).not.toBeInTheDocument();
    expect(screen.getByText((_, node) => node?.textContent === '当前处理人: 未指派')).toBeInTheDocument();
  });

  it('omits the tag row when there are no tags and editing is disabled', () => {
    render(<WorkitemMeta {...base} />);
    expect(screen.queryByText(/标签:/)).not.toBeInTheDocument();
  });

  it('shows read-only tags without an edit action', () => {
    render(<WorkitemMeta {...base} tags={['发布', '线上']} />);
    expect(screen.getByText('发布')).toBeInTheDocument();
    expect(screen.getByText('线上')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '编辑标签' })).not.toBeInTheDocument();
  });

  it('shows every tag without collapsing on the detail meta', () => {
    const tags = Array.from({ length: 12 }, (_, index) => `标签${index}`);
    render(<WorkitemMeta {...base} tags={tags} tagsEditable onEditTags={vi.fn()} />);
    tags.forEach((tag) => expect(screen.getByText(tag)).toBeInTheDocument());
    expect(screen.queryByTestId('workitem-tags-overflow')).not.toBeInTheDocument();
  });

  it('opens the tag editor from the meta edit action and shows an add entry when needed', async () => {
    const onEditTags = vi.fn();
    const { rerender } = render(
      <WorkitemMeta {...base} tags={['发布']} tagsEditable onEditTags={onEditTags} />,
    );
    expect(screen.getByText('发布')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '编辑标签' }));
    expect(onEditTags).toHaveBeenCalledTimes(1);

    rerender(<WorkitemMeta {...base} tags={[]} tagsEditable onEditTags={onEditTags} />);
    expect(screen.getByText('暂无标签')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '添加标签' }));
    expect(onEditTags).toHaveBeenCalledTimes(2);
  });
});

it('updates only a source-owned external assignee fallback while cached raw fields stay unchanged', async () => {
  const { i18n } = await import('@/shared/i18n');
  const { act } = await import('@testing-library/react');
  const raw = 'JIRA 用户（ID: null）';
  const props = { ...base, assigneeType: 'EXTERNAL', assigneeName: raw, assigneeDisplayName: raw, assigneeNameFallback: { provider: 'JIRA', subjectId: null, displayName: null } };
  const view = render(<WorkitemMeta {...props} />);
  expect(screen.getByText(raw)).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByText('JIRA user (ID: null)')).toBeInTheDocument();
  expect(props.assigneeName).toBe(raw);
  view.rerender(<WorkitemMeta {...props} assigneeNameFallback={undefined} />);
  expect(screen.getByText(raw)).toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});
