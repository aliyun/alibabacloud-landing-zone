import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MarkdownView } from './MarkdownView';

describe('MarkdownView', () => {
  it('ignores null member names when highlighting mentions', () => {
    render(
      <MarkdownView
        content="请 @有效成员 处理"
        mentionNames={[null, undefined, '  有效成员  ']}
      />,
    );

    expect(screen.getByText('@有效成员')).toBeInTheDocument();
  });

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

  it('contains a long fenced code line in a horizontally scrollable pre', () => {
    const longLine = `MCP error -32602: Structured content does not match the tool's output schema: ${'data/assigneeName must be string, '.repeat(8)}`;
    const { container } = render(
      <MarkdownView content={['```', longLine, '```'].join('\n')} />,
    );

    const pre = container.querySelector('pre');
    expect(pre).not.toBeNull();
    // jsdom 的 computed style 会省略默认值，直接断言内联声明
    expect(pre).toHaveStyle({ overflowX: 'auto', maxWidth: '100%' });
    expect(pre).toHaveTextContent('MCP error -32602');
  });

  it('keeps the reported workitem body code block contained without losing text', () => {
    const { container } = render(
      <MarkdownView
        content={[
          '工单详情页面中，工单正文部分情况会超出正文区域到右侧',
          '',
          '```',
          'autowonder_get_workitem (autowonder MCP Server)',
          'failed',
          '输出',
          "MCP error -32602: Structured content does not match the tool's output schema: data/assigneeName must be string, data/assigneeDisplayName must be string",
          '```',
        ].join('\n')}
      />,
    );

    expect(container).toHaveTextContent('工单详情页面中，工单正文部分情况会超出正文区域到右侧');
    const pre = container.querySelector('pre');
    expect(pre).not.toBeNull();
    expect(pre).toHaveStyle({ overflowX: 'auto', maxWidth: '100%' });
    expect(pre).toHaveTextContent('data/assigneeDisplayName must be string');
  });

  it('contains every pre when the content has several code blocks', () => {
    const { container } = render(
      <MarkdownView
        content={[
          '```',
          'a'.repeat(200),
          '```',
          '',
          '中间正文段落',
          '',
          '```json',
          `{"key":"${'v'.repeat(200)}"}`,
          '```',
        ].join('\n')}
      />,
    );

    const pres = container.querySelectorAll('pre');
    expect(pres).toHaveLength(2);
    pres.forEach((pre) => expect(pre).toHaveStyle({ overflowX: 'auto', maxWidth: '100%' }));
    expect(container).toHaveTextContent('中间正文段落');
  });

  it('does not pull inline code into the pre containment', () => {
    const { container } = render(
      <MarkdownView content={'正文里的 `inline-code` 不该被当成代码块'} />,
    );

    expect(container.querySelector('pre')).toBeNull();
    const code = container.querySelector('code');
    expect(code).not.toBeNull();
    expect(code).toHaveTextContent('inline-code');
  });

  it('keeps artifact paths inside fenced code blocks as contained code, not links', () => {
    const { container } = render(
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

    const pre = container.querySelector('pre');
    expect(pre).not.toBeNull();
    expect(pre).toHaveStyle({ overflowX: 'auto', maxWidth: '100%' });
    expect(pre).toHaveTextContent('artifacts/output/deliverables/report.md');
    expect(screen.queryByRole('button', { name: /打开产物/ })).not.toBeInTheDocument();
  });

  it('forces normal white-space so an inherited pre-wrap parent cannot create blank line gaps', () => {
    const { container } = render(
      <div style={{ whiteSpace: 'pre-wrap' }}>
        <MarkdownView content={'段落一：目标明确。\n\n段落二：范围收敛。'} />
      </div>,
    );

    // container 本身是 div，需多下钻一层才是 MarkdownView 根节点
    const mdRoot = container.querySelector('div > div > div');
    expect(mdRoot).not.toBeNull();
    // jsdom 的 computed style 会省略默认值，直接断言内联声明
    expect((mdRoot as HTMLElement).style.whiteSpace).toBe('normal');
    expect(screen.getByText(/段落一：目标明确。/)).toBeInTheDocument();
    expect(screen.getByText(/段落二：范围收敛。/)).toBeInTheDocument();
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

  it('renders gfm pipe tables as real tables', () => {
    const { container } = render(
      <MarkdownView
        content={['| 列A | 列B |', '| --- | --- |', '| 值1 | 值2 |'].join('\n')}
      />,
    );

    const table = container.querySelector('table');
    expect(table).not.toBeNull();
    expect(screen.getAllByRole('columnheader').map((el) => el.textContent)).toEqual(['列A', '列B']);
    expect(screen.getAllByRole('cell').map((el) => el.textContent)).toEqual(['值1', '值2']);
  });

  it('renders gfm strikethrough as del elements', () => {
    const { container } = render(<MarkdownView content="~~已废弃~~方案" />);

    const del = container.querySelector('del');
    expect(del).not.toBeNull();
    expect(del).toHaveTextContent('已废弃');
  });

  it('renders task list checkboxes as read-only markdown', () => {
    render(<MarkdownView content={'- [x] 完成\n- [ ] 待办'} />);
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes).toHaveLength(2);
    expect(boxes[0]).toBeChecked();
    expect(boxes[1]).not.toBeChecked();
    expect(boxes.every((box) => (box as HTMLInputElement).disabled)).toBe(true);
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

  it('skips null or undefined mention names instead of crashing render', () => {
    render(
      <MarkdownView
        content="请 @张三 确认结论"
        mentionNames={['张三', null, undefined, '   ']}
      />,
    );

    const mention = screen.getByText('@张三');
    expect(mention).toHaveAttribute('data-type', 'mention');
    const html = renderToStaticMarkup(<MarkdownView content="请 @张三 确认结论" mentionNames={['张三']} />);
    expect(html).toContain('color:var(--aw-accent-text)');
    expect(html).toContain('background-color:rgba(var(--aw-accent-rgb),.10)');
    expect(screen.getByText(/确认结论/)).toBeInTheDocument();
  });

  it('renders without crashing when content is null', () => {
    const { container } = render(<MarkdownView content={null as unknown as string} mentionNames={['张三']} />);
    expect(container.firstElementChild).toBeInTheDocument();
  });
});
