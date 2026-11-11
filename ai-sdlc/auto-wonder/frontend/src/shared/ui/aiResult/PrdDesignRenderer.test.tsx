import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { PrdDesignRenderer } from './PrdDesignRenderer';
import type { PrdDesignResult } from '@/shared/types/ai';

const base: PrdDesignResult = {
  title: '工单批量导入',
  goals: '让用户一次导入多个工单',
  inScope: ['CSV 上传', '失败行回执'],
  targetProjectAdaptation: '复用 frontend 应用的既有表格组件。',
  pages: [{ pageKey: 'import', name: '导入页', route: '/workitems/import' }],
  interfaces: [
    { name: '导入工单', method: 'POST', path: '/api/workitems/import', status: 'PLANNED' },
    { name: '查询工单', method: 'GET', path: '/api/workitems', status: 'EXISTING' },
  ],
  requirements: [
    { key: 'REQ-001', title: '上传 CSV', priority: 'P0', acceptanceCriteria: ['超过 10MB 时拒绝并说明原因'] },
    { key: 'REQ-002', title: '失败回执', acceptanceCriteria: ['列出失败行号'], excluded: true },
  ],
  openQuestions: [
    { key: 'Q-001', question: '导入上限是多少？', blocking: true, resolved: false },
  ],
  risks: ['大文件解析耗时'],
};

function renderRenderer(value: PrdDesignResult) {
  return render(
    <ConfigProvider locale={zhCN}>
      <PrdDesignRenderer value={value} onChange={() => {}} />
    </ConfigProvider>,
  );
}

describe('PrdDesignRenderer', () => {
  it('renders the title, requirements and their keys', () => {
    renderRenderer(base);

    expect(screen.getByText('工单批量导入')).toBeInTheDocument();
    expect(screen.getByText('REQ-001')).toBeInTheDocument();
    expect(screen.getByText('上传 CSV')).toBeInTheDocument();
  });

  it('warns about unresolved blocking questions so confirmation is not taken lightly', () => {
    renderRenderer(base);

    expect(screen.getByText('1 个阻塞问题待解决')).toBeInTheDocument();
  });

  it('does not warn when the only blocking question is resolved', () => {
    renderRenderer({
      ...base,
      openQuestions: [{ key: 'Q-001', question: '导入上限是多少？', blocking: true, resolved: true }],
    });

    expect(screen.queryByText('1 个阻塞问题待解决')).not.toBeInTheDocument();
  });

  it('flags planned interfaces as not yet existing', () => {
    renderRenderer(base);

    expect(screen.getByText('拟新增')).toBeInTheDocument();
    expect(screen.getByText('已有')).toBeInTheDocument();
  });

  it('marks an excluded requirement rather than hiding it', () => {
    renderRenderer(base);

    expect(screen.getByText('本次排除')).toBeInTheDocument();
    expect(screen.getByText('REQ-002')).toBeInTheDocument();
  });

  it('expands a requirement to reveal its acceptance criteria', async () => {
    const user = userEvent.setup();
    renderRenderer(base);

    // REQ-001 is the first expandable row; clicking its toggle reveals the criteria.
    await user.click(screen.getAllByRole('button', { name: '展开行' })[0]);

    expect(await screen.findByText('超过 10MB 时拒绝并说明原因')).toBeInTheDocument();
  });

  it('renders an empty state for a blank result', () => {
    renderRenderer({ title: '' });

    expect(screen.getByText('尚无设计内容')).toBeInTheDocument();
  });
});
