import { afterEach as resetAfterEach } from 'vitest';
import { act } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RunStatusTag } from './RunStatusTag';

describe('RunStatusTag', () => {
  it('renders labeled colored tags for known run statuses', () => {
    render(
      <>
        <RunStatusTag status="SUCCEEDED" />
        <RunStatusTag status="FAILED" />
        <RunStatusTag status="SKIPPED" />
        <RunStatusTag status="RUNNING" />
        <RunStatusTag status="CANCELED" />
      </>,
    );
    expect(screen.getByText('成功').closest('.ant-tag-success')).not.toBeNull();
    expect(screen.getByText('失败').closest('.ant-tag-error')).not.toBeNull();
    expect(screen.getByText('跳过').closest('.ant-tag-warning')).not.toBeNull();
    expect(screen.getByText('运行中').closest('.ant-tag-processing')).not.toBeNull();
    expect(screen.getByText('已取消').closest('.ant-tag-default')).not.toBeNull();
  });

  it('falls back to the raw status text for unknown statuses', () => {
    render(<RunStatusTag status="SERVER_NEW_STATUS" />);
    expect(screen.getByText('SERVER_NEW_STATUS')).toBeInTheDocument();
  });
});

it('switches all eleven known statuses and keeps prototype-like unknowns literal', async () => {
  const codes = ['QUEUED', 'STARTING', 'WAITING_EXECUTOR', 'WAITING_HUMAN', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELED', 'SKIPPED'];
  const { container } = render(<>{[...codes, 'toString', '__proto__'].map(status => <RunStatusTag key={status} status={status} />)}</>);
  expect(Array.from(container.querySelectorAll('.ant-tag')).map(tag => tag.textContent)).toEqual(['排队中', '启动中', '等待执行器', '等待人工处理', '运行中', '已暂停', '成功', '失败', '已超时', '已取消', '跳过', 'toString', '__proto__']);
  await act(() => i18n.changeLanguage('en-US'));
  expect(Array.from(container.querySelectorAll('.ant-tag')).map(tag => tag.textContent)).toEqual(['Queued', 'Starting', 'Waiting for executor', 'Waiting for human', 'Running', 'Paused', 'Succeeded', 'Failed', 'Timed out', 'Canceled', 'Skipped', 'toString', '__proto__']);
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  await act(() => i18n.changeLanguage('zh-CN'));
});

resetAfterEach(async () => { await act(() => i18n.changeLanguage('zh-CN')); });
