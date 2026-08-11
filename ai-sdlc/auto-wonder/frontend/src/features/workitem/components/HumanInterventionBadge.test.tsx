import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  HumanInterventionBadge,
  HumanInterventionAlert,
  getHumanInterventionName,
  isFinishedWorkitemStatus,
  stripAssigneeIdSuffix,
} from './HumanInterventionBadge';

describe('getHumanInterventionName', () => {
  it('returns display name for an explicitly assigned human', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    })).toBe('蔡何');
  });

  it('falls back to assigneeName when display name is missing', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: null,
    })).toBe('caihe');
  });

  it('falls back to user id when no names exist', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: null, assigneeDisplayName: null,
    })).toBe('用户 10000');
  });

  it('returns null for agent assignees', () => {
    expect(getHumanInterventionName({
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01', assigneeDisplayName: 'Coder-01',
    })).toBeNull();
  });

  it('returns null when no human is explicitly assigned', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null, assigneeDisplayName: null,
    })).toBeNull();
  });

  it('returns null for finished workitems even when assigned to a human', () => {
    const base = { assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何' };
    expect(getHumanInterventionName({ ...base, statusName: '已完成' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusName: '已修复' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusName: '已关闭' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusName: '已发布' })).toBeNull();
  });

  it('returns null for canceled workitems even when assigned to a human', () => {
    const base = { assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何' };
    expect(getHumanInterventionName({ ...base, statusName: '已取消' })).toBeNull();
    expect(getHumanInterventionName({ ...base, statusName: 'Canceled' })).toBeNull();
  });

  it('still returns the name for in-progress workitems', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何', statusName: '开发中',
    })).toBe('蔡何');
  });

  it('drops a trailing employee-id suffix from the display name', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    })).toBe('蔡何');
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何（10000）',
    })).toBe('蔡何');
  });

  it('drops the employee-id suffix from the assigneeName fallback too', () => {
    expect(getHumanInterventionName({
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe(10000)', assigneeDisplayName: null,
    })).toBe('caihe');
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

describe('isFinishedWorkitemStatus', () => {
  it('treats completed/fixed/closed/released names as finished', () => {
    expect(isFinishedWorkitemStatus('已完成')).toBe(true);
    expect(isFinishedWorkitemStatus('已修复')).toBe(true);
    expect(isFinishedWorkitemStatus('已关闭')).toBe(true);
    expect(isFinishedWorkitemStatus('已发布')).toBe(true);
    expect(isFinishedWorkitemStatus('DONE')).toBe(true);
    expect(isFinishedWorkitemStatus('Fixed')).toBe(true);
  });

  it('treats canceled names as finished', () => {
    expect(isFinishedWorkitemStatus('已取消')).toBe(true);
    expect(isFinishedWorkitemStatus('CANCELED')).toBe(true);
    expect(isFinishedWorkitemStatus('Cancelled')).toBe(true);
  });

  it('treats active or missing statuses as not finished', () => {
    expect(isFinishedWorkitemStatus('开发中')).toBe(false);
    expect(isFinishedWorkitemStatus('验证中')).toBe(false);
    expect(isFinishedWorkitemStatus('待处理')).toBe(false);
    expect(isFinishedWorkitemStatus(null)).toBe(false);
    expect(isFinishedWorkitemStatus(undefined)).toBe(false);
  });
});

describe('HumanInterventionBadge', () => {
  it('renders the warning tag with the assignee name for a human assignee', () => {
    render(<HumanInterventionBadge item={{
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    }} />);
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
  });

  it('renders the tag without the employee id when the display name carries one', () => {
    render(<HumanInterventionBadge item={{
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    }} />);
    expect(screen.getByText(/需人工（蔡何）/)).toBeInTheDocument();
    expect(screen.queryByText(/需人工（蔡何\(10000\)）/)).not.toBeInTheDocument();
  });

  it('renders nothing for agent assignees', () => {
    const { container } = render(<HumanInterventionBadge item={{
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01',
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when assigneeRef is null', () => {
    const { container } = render(<HumanInterventionBadge item={{
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a finished human-assigned workitem', () => {
    const base = { assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何' };
    const done = render(<HumanInterventionBadge item={{ ...base, statusName: '已完成' }} />);
    expect(done.container).toBeEmptyDOMElement();
    const fixed = render(<HumanInterventionBadge item={{ ...base, statusName: '已修复' }} />);
    expect(fixed.container).toBeEmptyDOMElement();
    const canceled = render(<HumanInterventionBadge item={{ ...base, statusName: '已取消' }} />);
    expect(canceled.container).toBeEmptyDOMElement();
  });
});

describe('HumanInterventionAlert', () => {
  it('renders the alert with name and guidance for a human assignee', () => {
    render(<HumanInterventionAlert item={{
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何',
    }} />);
    expect(screen.getByText('需人工介入：蔡何')).toBeInTheDocument();
    expect(screen.getByText('当前工单已指派给真人，请人工处理、补充决策，或重新指派给数字员工继续交付。')).toBeInTheDocument();
  });

  it('renders the alert without the employee id when the display name carries one', () => {
    render(<HumanInterventionAlert item={{
      assigneeType: 'HUMAN', assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何(10000)',
    }} />);
    expect(screen.getByText('需人工介入：蔡何')).toBeInTheDocument();
    expect(screen.queryByText(/蔡何\(10000\)/)).not.toBeInTheDocument();
  });

  it('renders nothing for agent assignees', () => {
    const { container } = render(<HumanInterventionAlert item={{
      assigneeType: 'AGENT', assigneeRef: 40013, assigneeName: 'Coder-01',
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when assigneeRef is null', () => {
    const { container } = render(<HumanInterventionAlert item={{
      assigneeType: 'HUMAN', assigneeRef: null, assigneeName: null,
    }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a finished human-assigned workitem', () => {
    const base = { assigneeType: 'HUMAN' as const, assigneeRef: 10000, assigneeName: 'caihe', assigneeDisplayName: '蔡何' };
    const done = render(<HumanInterventionAlert item={{ ...base, statusName: '已完成' }} />);
    expect(done.container).toBeEmptyDOMElement();
    const fixed = render(<HumanInterventionAlert item={{ ...base, statusName: '已修复' }} />);
    expect(fixed.container).toBeEmptyDOMElement();
    const canceled = render(<HumanInterventionAlert item={{ ...base, statusName: '已取消' }} />);
    expect(canceled.container).toBeEmptyDOMElement();
  });
});
