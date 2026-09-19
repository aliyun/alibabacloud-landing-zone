import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkitemMeta } from './WorkitemMeta';

const base = {
  priority: 1,
  assigneeName: 'caihe',
  assigneeDisplayName: '蔡何(10000)',
  assigneeType: 'HUMAN',
  creatorDisplayName: '淘飞(10018)',
  sdlcName: '标准流程',
};

describe('WorkitemMeta', () => {
  it('labels the assignee as 当前处理人 and strips id suffixes from names', () => {
    render(<WorkitemMeta {...base} />);
    expect(screen.getByText('当前处理人: 蔡何')).toBeInTheDocument();
    expect(screen.getByText('创建者: 淘飞')).toBeInTheDocument();
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
    expect(screen.getByText('当前处理人: 未指派')).toBeInTheDocument();
  });

  it('falls back to the login name when no display name exists', () => {
    render(<WorkitemMeta {...base} assigneeDisplayName={null} />);
    expect(screen.getByText('当前处理人: caihe')).toBeInTheDocument();
  });

  it('renders the Chinese priority label from the shared mapping', () => {
    render(<WorkitemMeta {...base} priority={0} />);
    expect(screen.getByText('紧急')).toHaveStyle({ color: '#ff4d4f' });
  });

  it('shows 未知优先级 for unknown priority values', () => {
    render(<WorkitemMeta {...base} priority={9} />);
    expect(screen.getByText('未知优先级')).toBeInTheDocument();
  });

  it('hides optional meta lines when absent', () => {
    render(
      <WorkitemMeta
        priority={2}
        assigneeName={null}
        assigneeType="HUMAN"
        creatorDisplayName={undefined}
        sdlcName={null}
      />,
    );
    expect(screen.queryByText(/SDLC/)).not.toBeInTheDocument();
    expect(screen.queryByText(/创建者/)).not.toBeInTheDocument();
    expect(screen.getByText('当前处理人: 未指派')).toBeInTheDocument();
  });
});
