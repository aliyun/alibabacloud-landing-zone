import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MarkdownView } from './MarkdownView';

describe('MarkdownView', () => {
  it('renders sanitized Aone html as readable rich text', () => {
    render(
      <MarkdownView
        content={'<article class="4ever-article"><p style="text-align:left"><span data-type="text">资源用例链接：</span><a href="https://api.example.com/#/autotest?groupId=1&amp;quickItem=released" onclick="alert(1)" target="_blank" rel="noopener noreferrer"><span data-type="text">https://api.example.com/#/autotest?groupId=1&amp;quickItem=released</span></a></p></article>'}
      />,
    );

    expect(screen.getByText('资源用例链接：')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'https://api.example.com/#/autotest?groupId=1&quickItem=released' });
    expect(link).toHaveAttribute('href', 'https://api.example.com/#/autotest?groupId=1&quickItem=released');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).not.toHaveAttribute('onclick');
  });

  it('allows long urls and json snippets to wrap inside workitem content', () => {
    const { container } = render(
      <MarkdownView
        content={'https://api.example.com/#/autotest/testcase?tenantUuid=1937c68ef059446285d1cf73b3ca9635&groupId=9404ce7a251547d1bf7fedc97be7daf1\n\n{"RequestId":"286BE045-6290-55A7-AF7C-AAD07A9EB421","Message":"ReplicaPairId is mandatory for this action."}'}
      />,
    );

    expect(container.firstElementChild).toHaveStyle({
      overflowWrap: 'anywhere',
      wordBreak: 'break-word',
      maxWidth: '100%',
    });
  });

  it('renders a matched artifact path as a clickable link', async () => {
    const onArtifactClick = vi.fn();
    render(
      <MarkdownView
        content="证据：artifacts/output/deliverables/report.md"
        artifacts={[{
          id: 7,
          workitemId: 1,
          dispatchId: 2,
          name: 'deliverables/report.md',
          type: 'DELIVERABLE',
          size: 100,
          gmtCreate: '2026-07-28T10:00:00Z',
        }]}
        onArtifactClick={onArtifactClick}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: '打开产物 artifacts/output/deliverables/report.md' }));

    expect(onArtifactClick).toHaveBeenCalledWith(expect.objectContaining({ id: 7 }));
  });

  it('renders task package artifact paths as clickable links', async () => {
    const onArtifactClick = vi.fn();
    render(
      <MarkdownView
        content="测试报告：artifacts/input/teammates/测试工程师/artifacts/output/deliverables/step-400174-test-report.md"
        artifacts={[
          {
            id: 26007,
            workitemId: 12636,
            dispatchId: 10834,
            name: 'artifacts/output/deliverables/step-400174-test-report.md',
            type: 'DELIVERABLE',
            size: 3439,
            gmtCreate: '2026-07-28T10:00:00Z',
          },
          {
            id: 51432,
            workitemId: 12636,
            dispatchId: 10851,
            name: 'artifacts/output/deliverables/step-400174-test-report.md',
            type: 'DELIVERABLE',
            size: 3439,
            gmtCreate: '2026-07-28T12:00:00Z',
          },
        ]}
        onArtifactClick={onArtifactClick}
      />,
    );

    await userEvent.click(screen.getByRole('button', {
      name: '打开产物 artifacts/input/teammates/测试工程师/artifacts/output/deliverables/step-400174-test-report.md',
    }));

    expect(onArtifactClick).toHaveBeenCalledWith(expect.objectContaining({ id: 51432 }));
  });

  it('renders backticked artifact paths as clickable links', async () => {
    const onArtifactClick = vi.fn();
    render(
      <MarkdownView
        content={[
          '## 证据',
          '- 完成报告：`artifacts/output/deliverables/step-400176-completion-report.md`',
          '- 部署核验：`artifacts/output/evidence/step-400175-aone-mix-capability-assessment.md`',
          '- 测试报告：`artifacts/input/teammates/测试工程师/artifacts/output/deliverables/step-400174-test-report.md`',
        ].join('\n')}
        artifacts={[
          {
            id: 51418,
            workitemId: 12636,
            dispatchId: 10851,
            name: 'artifacts/output/deliverables/step-400176-completion-report.md',
            type: 'DELIVERABLE',
            size: 6044,
            gmtCreate: '2026-07-28T10:00:00Z',
          },
          {
            id: 51415,
            workitemId: 12636,
            dispatchId: 10851,
            name: 'artifacts/output/evidence/step-400175-aone-mix-capability-assessment.md',
            type: 'EVIDENCE',
            size: 3865,
            gmtCreate: '2026-07-28T10:00:00Z',
          },
          {
            id: 26007,
            workitemId: 12636,
            dispatchId: 10834,
            name: 'artifacts/output/deliverables/step-400174-test-report.md',
            type: 'DELIVERABLE',
            size: 3439,
            gmtCreate: '2026-07-27T10:00:00Z',
          },
          {
            id: 51432,
            workitemId: 12636,
            dispatchId: 10851,
            name: 'artifacts/output/deliverables/step-400174-test-report.md',
            type: 'DELIVERABLE',
            size: 3439,
            gmtCreate: '2026-07-28T12:00:00Z',
          },
        ]}
        onArtifactClick={onArtifactClick}
      />,
    );

    await userEvent.click(screen.getByRole('button', {
      name: '打开产物 artifacts/output/deliverables/step-400176-completion-report.md',
    }));
    await userEvent.click(screen.getByRole('button', {
      name: '打开产物 artifacts/output/evidence/step-400175-aone-mix-capability-assessment.md',
    }));
    await userEvent.click(screen.getByRole('button', {
      name: '打开产物 artifacts/input/teammates/测试工程师/artifacts/output/deliverables/step-400174-test-report.md',
    }));

    expect(onArtifactClick).toHaveBeenNthCalledWith(1, expect.objectContaining({ id: 51418 }));
    expect(onArtifactClick).toHaveBeenNthCalledWith(2, expect.objectContaining({ id: 51415 }));
    expect(onArtifactClick).toHaveBeenNthCalledWith(3, expect.objectContaining({ id: 51432 }));
  });

  it('keeps artifact-looking paths inside fenced code blocks as code', () => {
    render(
      <MarkdownView
        content={'```text\nartifacts/output/deliverables/report.md\n```'}
        artifacts={[{
          id: 7,
          workitemId: 1,
          dispatchId: 2,
          name: 'artifacts/output/deliverables/report.md',
          type: 'DELIVERABLE',
          size: 100,
          gmtCreate: '2026-07-28T10:00:00Z',
        }]}
      />,
    );

    expect(screen.getByText('artifacts/output/deliverables/report.md')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /打开产物/ })).not.toBeInTheDocument();
  });

  it('keeps unmatched artifact-looking paths as plain text', () => {
    render(<MarkdownView content="证据：artifacts/output/deliverables/missing.md" artifacts={[]} />);

    expect(screen.getByText(/artifacts\/output\/deliverables\/missing.md/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /打开产物/ })).not.toBeInTheDocument();
  });

  it('does not link artifact-looking paths inside existing markdown links or urls', () => {
    const { container } = render(
      <MarkdownView
        content={'[报告](artifacts/output/deliverables/report.md)\nhttps://example.com/deliverables/report.md\n证据：artifacts/output/deliverables/report.md'}
        artifacts={[{
          id: 7,
          workitemId: 1,
          dispatchId: 2,
          name: 'deliverables/report.md',
          type: 'DELIVERABLE',
          size: 100,
          gmtCreate: '2026-07-28T10:00:00Z',
        }]}
      />,
    );

    expect(screen.getByRole('link', { name: '报告' })).toHaveAttribute('href', 'artifacts/output/deliverables/report.md');
    expect(container).toHaveTextContent('https://example.com/deliverables/report.md');
    expect(screen.getAllByRole('button', { name: /打开产物/ })).toHaveLength(1);
  });
});
