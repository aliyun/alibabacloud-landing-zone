import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AgentConfigGenRenderer } from './AgentConfigGenRenderer';
import type { AgentConfigGenResult } from '@/shared/types/ai';

const draft: AgentConfigGenResult = {
  name: 'Terraform 工单分诊助手',
  avatarUrl: '',
  roleName: '工单分诊专员',
  roleCode: 'TERRAFORM_TRIAGE',
  businessBackground: '负责 Terraform 相关工单分诊。',
  responsibilities: '分析需求、识别负责人并输出处理建议。',
  missingFields: ['workflow'],
  clarifyingQuestions: ['是否需要绑定默认处理流程？'],
  recommendations: {
    executors: ['CLI 执行器'],
    skills: ['Terraform'],
    memories: ['基础设施规范'],
    workflows: ['工单分诊流程'],
  },
};

describe('AgentConfigGenRenderer', () => {
  it('renders editable draft, missing fields and recommendations', () => {
    render(<AgentConfigGenRenderer value={draft} onChange={vi.fn()} disabled={false} />);

    expect(screen.getByText('有待补充信息')).toBeInTheDocument();
    expect(screen.getByText('workflow')).toBeInTheDocument();
    expect(screen.getByText('是否需要绑定默认处理流程？')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Terraform 工单分诊助手')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Terraform')).toBeInTheDocument();
  });

  it('normalizes roleCode edits', async () => {
    const onChange = vi.fn();
    render(<AgentConfigGenRenderer value={{ ...draft, roleCode: '' }} onChange={onChange} disabled={false} />);

    fireEvent.change(screen.getAllByRole('textbox')[3], { target: { value: 'ops triage' } });

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ roleCode: 'OPS_TRIAGE' }));
  });
});
