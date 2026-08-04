import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SdlcGenRenderer } from './SdlcGenRenderer';
import type { SdlcGenResult } from '@/shared/types/ai';

const base: SdlcGenResult = {
  name: '默认流程',
  description: '单个数字员工内部研发流程',
  steps: [
    {
      order: 1,
      name: '需求满足性分析',
      kind: 'analysis',
      instructionMd: '判断当前上下文是否足够支撑完成任务，不足时反馈给需求指派人。',
      checklist: ['确认上下文完整', '识别缺失信息'],
      gatePolicy: { passCriteria: '上下文完整或已反馈缺失信息' },
      required: true,
      timeoutSeconds: 300,
      retryBudget: 0,
    },
    {
      order: 2,
      name: '编码实现',
      kind: 'implementation',
      instructionMd: '基于 worktree 完成代码实现并查看 diff。',
      checklist: ['完成代码实现'],
      gatePolicy: { passCriteria: 'diff 符合预期' },
      required: true,
    },
  ],
};

describe('SdlcGenRenderer', () => {
  it('renders flow name and steps', () => {
    render(<SdlcGenRenderer value={base} onChange={() => {}} />);
    expect(screen.getByDisplayValue('默认流程')).toBeInTheDocument();
    expect(screen.getByDisplayValue('单个数字员工内部研发流程')).toBeInTheDocument();
    expect(screen.getByDisplayValue('需求满足性分析')).toBeInTheDocument();
    expect(screen.getByText('analysis')).toBeInTheDocument();
    expect(screen.getByDisplayValue(/判断当前上下文是否足够/)).toBeInTheDocument();
    expect(screen.queryByText('角色码')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/角色码/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/成功流转/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/失败流转/)).not.toBeInTheDocument();
  });

  it('moving a step up reindexes order', async () => {
    const onChange = vi.fn();
    render(<SdlcGenRenderer value={base} onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: /上移第2步/ }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      name: '默认流程',
      description: '单个数字员工内部研发流程',
      steps: [
        expect.objectContaining({ order: 1, name: '编码实现' }),
        expect.objectContaining({ order: 2, name: '需求满足性分析' }),
      ],
    }));
  });

  it('adds a step', async () => {
    const onChange = vi.fn();
    render(<SdlcGenRenderer value={base} onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: /新增步骤/ }));
    const call = onChange.mock.calls[onChange.mock.calls.length - 1][0] as SdlcGenResult;
    expect(call.steps).toHaveLength(3);
    expect(call.steps[2].order).toBe(3);
    expect(call.steps[2]).toEqual(expect.objectContaining({
      kind: 'analysis',
      instructionMd: '',
      checklist: [],
      gatePolicy: { passCriteria: '' },
      required: true,
    }));
  });

  it('deletes a step', async () => {
    const onChange = vi.fn();
    render(<SdlcGenRenderer value={base} onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: /删除第1步/ }));
    const call = onChange.mock.calls[onChange.mock.calls.length - 1][0] as SdlcGenResult;
    expect(call.steps).toHaveLength(1);
    expect(call.steps[0]).toEqual(expect.objectContaining({ order: 1, name: '编码实现' }));
  });

  it('edits instruction and checklist as structured workflow content', async () => {
    const onChange = vi.fn();
    render(<SdlcGenRenderer value={base} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('执行说明 1'), { target: { value: '新的详细执行说明' } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      steps: [
        expect.objectContaining({ instructionMd: '新的详细执行说明' }),
        expect.any(Object),
      ],
    }));
  });
});
