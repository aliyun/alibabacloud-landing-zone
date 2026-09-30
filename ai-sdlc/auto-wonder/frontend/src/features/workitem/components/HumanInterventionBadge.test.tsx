import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  HumanInterventionBadge,
  HumanInterventionAlert,
  getHumanInterventionName,
  isHumanInterventionRequired,
} from './HumanInterventionBadge';
import { stripAssigneeIdSuffix } from '../nameDisplay';

describe('getHumanInterventionName', () => {
  it('marks external pending decisions as unclaimed without naming a local owner', () => {
    const item = { statusCategory: 'PENDING_DECISION', assigneeType: 'EXTERNAL' as const,
      assigneeRef: 0, assigneeName: '来源负责人' };
    expect(getHumanInterventionName(item)).toBe('待认领');
    expect(getHumanInterventionName({ ...item, statusCategory: 'IN_PROGRESS' })).toBeNull();
  });
  it('returns display name for a pending-decision workitem assigned to a human', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    })).toBe('蔡何');
  });

  it('falls back to assigneeName when display name is missing', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: null,
    })).toBe('caihe');
  });

  it('falls back to user id when no names exist', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: null, assigneeDisplayName: null,
    })).toBe('用户 10000');
  });

  it('returns null for agent assignees', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01', assigneeDisplayName: 'Coder-01',
    })).toBeNull();
  });

  it('returns null when no human is explicitly assigned', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, assigneeDisplayName: null,
    })).toBeNull();
  });

  it('returns null outside the pending-decision category even for human assignees（规格 3.2）', () => {
    const base = {
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    };
    expect(getHumanInterventionName({ ...base, statusCategory: 'NEW' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusCategory: 'IN_PROGRESS' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusCategory: 'DONE' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusCategory: 'CANCELED' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusCategory: null })).toBeNull();
  });

  it('ignores status names entirely（名称仅展示，规格 3.1/3.2）', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION', statusName: '已完成',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    } as Parameters<typeof getHumanInterventionName>[0])).toBe('蔡何');
    expect(getHumanInterventionName({
      statusCategory: 'DONE', statusName: '开发中',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    } as Parameters<typeof getHumanInterventionName>[0])).toBeNull();
  });

  it('drops a trailing employee-id suffix from the display name', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    })).toBe('蔡何');
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何（10000）',
    })).toBe('蔡何');
  });

  it('drops the employee-id suffix from the assigneeName fallback too', () => {
    expect(getHumanInterventionName({
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe(10000)', assigneeDisplayName: null,
    })).toBe('caihe');
  });
});

describe('isHumanInterventionRequired', () => {
  it('is true only for the pending-decision category（与「待决策」同条件）', () => {
    expect(isHumanInterventionRequired({ statusCategory: 'PENDING_DECISION' })).toBe(true);
    expect(isHumanInterventionRequired({ statusCategory: 'NEW' })).toBe(false);
    expect(isHumanInterventionRequired({ statusCategory: 'IN_PROGRESS' })).toBe(false);
    expect(isHumanInterventionRequired({ statusCategory: 'DONE' })).toBe(false);
    expect(isHumanInterventionRequired({ statusCategory: 'CANCELED' })).toBe(false);
    expect(isHumanInterventionRequired({ statusCategory: null })).toBe(false);
    expect(isHumanInterventionRequired({})).toBe(false);
  });
});

describe('stripAssigneeIdSuffix', () => {
  it('strips trailing parenthesized numeric ids (half/full-width)', () => {
    expect(stripAssigneeIdSuffix('蔡何(10000)')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('蔡何（10000）')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('真人 (10000)')).toBe('真人');
  });

  it('keeps names without a numeric id suffix untouched', () => {
    expect(stripAssigneeIdSuffix('蔡何')).toBe('蔡何');
    expect(stripAssigneeIdSuffix('Coder-01')).toBe('Coder-01');
    expect(stripAssigneeIdSuffix('蔡何(产品)')).toBe('蔡何(产品)');
  });

  it('keeps the original value when stripping would leave it empty', () => {
    expect(stripAssigneeIdSuffix('(10000)')).toBe('(10000)');
  });
});

describe('HumanInterventionBadge', () => {
  it('renders the warning tag with the assignee name for a pending-decision workitem', () => {
    render(<HumanInterventionBadge item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    }} />);
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
  });

  it('renders the tag without the employee id when the display name carries one', () => {
    render(<HumanInterventionBadge item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    }} />);
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
    expect(screen.queryByText(/需人工（蔡何\(10000\)）/)).not.toBeInTheDocument();
  });

  it('renders nothing for agent assignees', () => {
    const { container } = render(<HumanInterventionBadge item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01',
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when assigneeRef is null', () => {
    const { container } = render(<HumanInterventionBadge item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for human assignees outside the pending-decision category', () => {
    const base = {
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    };
    const created = render(<HumanInterventionBadge item={{ ...base, statusCategory: 'NEW' }} />);
    expect(created.container).toBeEmptyDOMElement();
    const done = render(<HumanInterventionBadge item={{ ...base, statusCategory: 'DONE', statusName: '开发中' }} />);
    expect(done.container).toBeEmptyDOMElement();
    const canceled = render(<HumanInterventionBadge item={{ ...base, statusCategory: 'CANCELED' }} />);
    expect(canceled.container).toBeEmptyDOMElement();
  });
});

describe('HumanInterventionAlert', () => {
  it('shows an unclaimed badge and ownership guidance for external pending decisions', () => {
    const item = { statusCategory: 'PENDING_DECISION', assigneeType: 'EXTERNAL' as const,
      assigneeRef: 0, assigneeName: null };
    render(<><HumanInterventionBadge item={item} /><HumanInterventionAlert item={item} /></>);
    expect(screen.getByText('需人工（待认领）')).toBeInTheDocument();
    expect(screen.getByText('需人工介入：待认领')).toBeInTheDocument();
    expect(screen.getByText('当前工单尚未指派本地负责人，请指派真人接手，或指派数字员工继续交付。')).toBeInTheDocument();
  });
  it('renders the alert with name and guidance for a pending-decision workitem', () => {
    render(<HumanInterventionAlert item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    }} />);
    expect(screen.getByText('需人工介入：蔡何')).toBeInTheDocument();
    expect(screen.getByText('当前工单已指派给真人，请人工处理、补充决策，或重新指派给数字员工继续交付。')).toBeInTheDocument();
  });

  it('renders the alert without the employee id when the display name carries one', () => {
    render(<HumanInterventionAlert item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    }} />);
    expect(screen.getByText('需人工介入：蔡何')).toBeInTheDocument();
    expect(screen.queryByText(/蔡何\(10000\)/)).not.toBeInTheDocument();
  });

  it('renders nothing for agent assignees', () => {
    const { container } = render(<HumanInterventionAlert item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01',
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when assigneeRef is null', () => {
    const { container } = render(<HumanInterventionAlert item={{
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for human assignees outside the pending-decision category', () => {
    const base = {
      statusCategory: 'PENDING_DECISION',
      assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    };
    const created = render(<HumanInterventionAlert item={{ ...base, statusCategory: 'NEW' }} />);
    expect(created.container).toBeEmptyDOMElement();
    const done = render(<HumanInterventionAlert item={{ ...base, statusCategory: 'DONE' }} />);
    expect(done.container).toBeEmptyDOMElement();
    const canceled = render(<HumanInterventionAlert item={{ ...base, statusCategory: 'CANCELED' }} />);
    expect(canceled.container).toBeEmptyDOMElement();
  });
});
