import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AgentVersion } from '../api';
import { AgentVersionDiff } from './AgentVersionDiff';
import { useNarrowDiff } from './diff/useNarrowDiff';

function NarrowProbe() {
  const narrow = useNarrowDiff();
  return <span>{narrow ? 'narrow' : 'wide'}</span>;
}

function version(overrides: Partial<AgentVersion> = {}): AgentVersion {
  return {
    id: 1,
    agentId: 1,
    versionNo: 12,
    status: 'PENDING_REVIEW',
    roleName: 'Frontend Dev',
    roleCode: 'FRONTEND_DEV',
    businessBackground: 'line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10',
    responsibilities: 'old responsibility',
    sdlcId: 1,
    identityJson: '{"evolutionMode":"ASSISTED"}',
    reviewerId: null,
    reviewComment: null,
    reviewedAt: null,
    version: 0,
    gmtCreate: '2026-10-29T10:00:00Z',
    repoPerms: [],
    skills: [],
    memoryRefs: [],
    environmentVariables: [],
    ...overrides,
  };
}

function changedVersions() {
  const base = version({
    businessBackground: 'line 1\nline 2\nline 3\nold line\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10',
  });
  const target = version({
    versionNo: 13,
    businessBackground: 'line 1\nline 2\nline 3\nnew line\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10',
    responsibilities: 'new responsibility',
    roleName: 'Architect',
    sdlcId: 2,
    identityJson: '{"evolutionMode":"AUTO_PROPOSAL"}',
    submitNote: '升级架构并收窄权限',
    creatorId: 7,
    creatorName: '蔡何',
    repoPerms: [
      { repoId: 1, repoName: 'auto-wonder', permLevel: 'WRITE', allowedBranchPatterns: ['feature/*'] },
      { repoId: 2, repoName: 'legacy', permLevel: 'ADMIN', allowedBranchPatterns: [] },
    ],
    skills: [{ skillId: 4, skillName: 'reviewer' }],
    memoryRefs: [{ memoryId: 9, source: 'SQUAD_IMPORT', title: '发布规范' }],
    environmentVariables: [{ id: 3, name: 'TOKEN', description: 'token desc', value: '**' }],
  });
  return { base, target };
}

function section(key: string): HTMLElement {
  return screen.getByTestId(`awdiff-section-${key}`);
}

describe('AgentVersionDiff', () => {
  let scrollSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy as unknown as Element['scrollIntoView'];
  });

  afterEach(() => {
    vi.restoreAllMocks();
    // @ts-expect-error jsdom does not implement scrollIntoView.
    delete Element.prototype.scrollIntoView;
  });

  it('renders the seven groups with split diff rows and the submit note', () => {
    const { base, target } = changedVersions();
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    expect(screen.getAllByTestId(/^awdiff-section-/)).toHaveLength(7);
    expect(screen.getByText('升级架构并收窄权限')).toBeInTheDocument();
    expect(screen.getByText(/提交人：蔡何/)).toBeInTheDocument();
    expect(screen.getByText('v12')).toBeInTheDocument();
    expect(screen.getByText('v13')).toBeInTheDocument();

    const soul = section('soul');
    expect(within(soul).getByText('old line').closest('tr')).toHaveClass('awdiff-row-del');
    expect(within(soul).getByText('new line').closest('tr')).toHaveClass('awdiff-row-add');
    expect(within(soul).getByText('在线版本 v12')).toBeInTheDocument();
    expect(within(soul).getByText('待审核版本 v13')).toBeInTheDocument();
  });

  it('shows collection changes and annotation badges', () => {
    const { base, target } = changedVersions();
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    expect(within(section('repoPerms')).getByText('auto-wonder')).toBeInTheDocument();
    expect(within(section('repoPerms')).getByText('legacy')).toBeInTheDocument();
    expect(within(section('skills')).getByText('reviewer')).toBeInTheDocument();
    expect(within(section('memoryRefs')).getByText('发布规范')).toBeInTheDocument();
    expect(within(section('memoryRefs')).getByText('系统自动导入')).toBeInTheDocument();
    expect(within(section('environmentVariables')).getByText(/仅对比绑定关系与元数据/)).toBeInTheDocument();
    expect(within(section('environmentVariables')).getAllByText('**').length).toBeGreaterThan(0);
  });

  it('switches between split and unified layouts', async () => {
    const { base, target } = changedVersions();
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    expect(screen.getAllByTestId('awdiff-split-table').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: '切换单栏' }));
    expect(screen.queryAllByTestId('awdiff-split-table')).toHaveLength(0);
    expect(screen.getAllByTestId('awdiff-unified-table').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: '切换分栏' }));
    expect(screen.getAllByTestId('awdiff-split-table').length).toBeGreaterThan(0);
  });

  it('collapses unchanged context and expands it on demand', async () => {
    const lines = Array.from({ length: 40 }, (_, index) => `line ${index + 1}`);
    const targetLines = [...lines];
    targetLines[19] = 'changed line';
    const base = version({ businessBackground: lines.join('\n') });
    const target = version({ versionNo: 13, businessBackground: targetLines.join('\n') });
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    const soul = section('soul');
    expect(within(soul).getAllByText(/未变更的 \d+ 行/)).toHaveLength(2);
    await userEvent.click(within(soul).getAllByRole('button', { name: '展开上下文' })[0]);
    expect(within(soul).getAllByText(/未变更的 \d+ 行/)).toHaveLength(1);
  });

  it('navigates between change blocks', async () => {
    const base = version({ businessBackground: 'a\nb\nc\nd\ne\nf\ng\nh' });
    const target = version({
      versionNo: 13,
      businessBackground: 'a\nB\nc\nd\ne\nf\nG\nh',
    });
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    const soul = section('soul');
    const counter = within(soul).getByTestId('awdiff-nav-count-soul');
    expect(counter).toHaveTextContent('1 / 2');
    await userEvent.click(within(soul).getByRole('button', { name: '下一处' }));
    expect(counter).toHaveTextContent('2 / 2');
    expect(scrollSpy).toHaveBeenCalled();
  });

  it('expands all context at once', async () => {
    const lines = Array.from({ length: 40 }, (_, index) => `line ${index + 1}`);
    const targetLines = [...lines];
    targetLines[19] = 'changed line';
    const base = version({ businessBackground: lines.join('\n') });
    const target = version({ versionNo: 13, businessBackground: targetLines.join('\n') });
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    const soul = section('soul');
    await userEvent.click(within(soul).getByRole('button', { name: '展开全部' }));
    expect(within(soul).queryByText(/未变更的/)).not.toBeInTheDocument();
  });

  it('shows unchanged configuration fields only after toggling them on', async () => {
    const base = version({ sdlcId: 1, identityJson: '{"evolutionMode":"ASSISTED"}' });
    const target = version({ versionNo: 13, roleName: 'Architect' });
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    const config = section('config');
    expect(within(config).queryByText('SDLC流程')).not.toBeInTheDocument();
    await userEvent.click(within(config).getByRole('switch'));
    expect(within(config).getByText('SDLC流程')).toBeInTheDocument();
  });

  it('renders a first version as additions only without navigation', () => {
    render(<AgentVersionDiff base={null} target={version({ versionNo: 13, businessBackground: 'a\nb' })} variant="modal" />);

    expect(screen.getByText(/该数字员工尚无在线版本/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '上一处' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('awdiff-split-table')).not.toBeInTheDocument();
    expect(screen.getAllByTestId('awdiff-unified-table').length).toBeGreaterThan(0);
    expect(screen.queryByText(/^−/)).not.toBeInTheDocument();
  });

  it('degrades with a notice when the baseline version is unavailable', () => {
    render(<AgentVersionDiff base={null} target={version({ versionNo: 13 })} variant="modal" baselineUnavailable />);

    expect(screen.getByText(/基线版本已不可用/)).toBeInTheDocument();
    expect(screen.queryByText(/该数字员工尚无在线版本/)).not.toBeInTheDocument();
  });

  it('reports when nothing differs at all', () => {
    render(<AgentVersionDiff base={version()} target={version()} variant="modal" />);

    expect(screen.getByText('本次提交与在线版本无差异。')).toBeInTheDocument();
    expect(screen.getAllByText('无变化').length).toBeGreaterThan(0);
  });

  it('shows an empty hint for blank documents', () => {
    render(<AgentVersionDiff base={version({ businessBackground: '' })} target={version({ versionNo: 13, businessBackground: '' })} variant="modal" />);

    expect(within(section('soul')).getByText('无内容')).toBeInTheDocument();
  });

  it('scrolls to a group when its chip is clicked', async () => {
    const { base, target } = changedVersions();
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    await userEvent.click(screen.getByRole('button', { name: /仓库权限/ }));
    expect(scrollSpy).toHaveBeenCalled();
  });

  it('renders modified permissions, branch patterns and environment descriptions', () => {
    const base = version({
      repoPerms: [{ repoId: 1, repoName: 'auto-wonder', permLevel: 'READ', allowedBranchPatterns: ['main'] }],
      environmentVariables: [{ id: 3, name: 'JIRA_URL', description: '旧描述', value: '**' }],
    });
    const target = version({
      versionNo: 13,
      repoPerms: [{ repoId: 1, repoName: 'auto-wonder', permLevel: 'WRITE', allowedBranchPatterns: ['feature/*'] }],
      environmentVariables: [{ id: 3, name: 'JIRA_URL', description: '新描述', value: '**' }],
    });
    render(<AgentVersionDiff base={base} target={target} variant="modal" />);

    const repos = section('repoPerms');
    // 修改态同时有标题旁的变更标签与行尾徽标，两处都渲染同一文案。
    expect(within(repos).getAllByText('修改').length).toBeGreaterThan(0);
    expect(within(repos).getByText('只读')).toBeInTheDocument();
    expect(within(repos).getByText('读写')).toBeInTheDocument();
    expect(within(repos).getByText('main')).toBeInTheDocument();
    expect(within(repos).getByText('feature/*')).toBeInTheDocument();

    const env = section('environmentVariables');
    expect(within(env).getByText('旧描述')).toBeInTheDocument();
    expect(within(env).getByText('新描述')).toBeInTheDocument();
  });

  it('keeps an expanded fold when the context window changes', async () => {
    const lines = Array.from({ length: 40 }, (_, index) => `line ${index + 1}`);
    const targetLines = [...lines];
    targetLines[19] = 'changed line';
    render(
      <AgentVersionDiff
        base={version({ businessBackground: lines.join('\n') })}
        target={version({ versionNo: 13, businessBackground: targetLines.join('\n') })}
        variant="modal"
      />,
    );

    const soul = section('soul');
    expect(within(soul).getAllByText(/未变更的 \d+ 行/)).toHaveLength(2);
    await userEvent.click(within(soul).getAllByRole('button', { name: '展开上下文' })[0]);
    expect(within(soul).getAllByText(/未变更的 \d+ 行/)).toHaveLength(1);

    await userEvent.click(within(soul).getByRole('combobox', { name: '上下文' }));
    await userEvent.click(await screen.findByRole('option', { name: '5 行' }));

    // The expanded leading gap keeps its identity, so only the trailing gap is still folded.
    expect(within(soul).getAllByText(/未变更的 \d+ 行/)).toHaveLength(1);
  });

  it('warns and renders only change blocks for very large documents', () => {
    const lines = Array.from({ length: 3001 }, (_, index) => `line ${index + 1}`);
    render(
      <AgentVersionDiff
        base={version({ businessBackground: lines.join('\n') })}
        target={version({ versionNo: 13, businessBackground: `${lines.join('\n')}\nextra` })}
        variant="modal"
      />,
    );

    expect(screen.getByText(/文档较长/)).toBeInTheDocument();
    expect(within(section('soul')).getByText('extra').closest('tr')).toHaveClass('awdiff-row-add');
  });
});

describe('useNarrowDiff', () => {
  it('falls back to the wide layout when matchMedia is unavailable', () => {
    const original = window.matchMedia;
    (window as unknown as { matchMedia?: unknown }).matchMedia = undefined;
    try {
      render(<NarrowProbe />);
      expect(screen.getByText('wide')).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });

  it('follows a viewport that becomes narrow', async () => {
    const original = window.matchMedia;
    let listener: ((event: { matches: boolean }) => void) | undefined;
    (window as unknown as { matchMedia: unknown }).matchMedia = (query: string) => ({
      matches: false,
      media: query,
      addEventListener: (_type: string, callback: (event: { matches: boolean }) => void) => {
        listener = callback;
      },
      removeEventListener: () => undefined,
    });
    try {
      render(<NarrowProbe />);
      expect(screen.getByText('wide')).toBeInTheDocument();

      await act(async () => { listener?.({ matches: true }); });

      expect(screen.getByText('narrow')).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });
});
