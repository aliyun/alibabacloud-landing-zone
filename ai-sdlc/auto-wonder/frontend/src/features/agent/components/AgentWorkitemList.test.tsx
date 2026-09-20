import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AgentWorkitemList } from './AgentWorkitemList';
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

describe('AgentWorkitemList 优先级展示', () => {
  it('renders the shared Chinese priority labels with their colors', () => {
    render(
      <MemoryRouter>
        <AgentWorkitemList workitems={[
          mk({ id: 1, title: '紧急任务', priority: 0 }),
          mk({ id: 2, title: '低优任务', priority: 3 }),
        ]} />
      </MemoryRouter>,
    );

    expect(screen.getByText('紧急')).toHaveStyle({ color: '#ff4d4f' });
    expect(screen.getByText('低')).toBeInTheDocument();
  });

  it('shows 未知优先级 for unknown priority values', () => {
    render(
      <MemoryRouter>
        <AgentWorkitemList workitems={[mk({ id: 1, title: '未知任务', priority: 9 })]} />
      </MemoryRouter>,
    );

    expect(screen.getByText('未知优先级')).toBeInTheDocument();
  });
});
