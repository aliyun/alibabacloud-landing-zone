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
    render(<RunStatusTag status="TIMED_OUT" />);
    expect(screen.getByText('TIMED_OUT')).toBeInTheDocument();
  });
});
