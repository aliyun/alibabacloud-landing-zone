import { useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Select, Switch } from 'antd';
import { formatDateTime } from '@/shared/i18n/format';
import type { AgentVersion } from '../api';
import { buildRenderItems, type LineDiffResult } from './diff/lineDiff';
import {
  buildVersionDiff,
  type DiffGroup,
  type DiffGroupKey,
  type VersionDiffModel,
} from './diff/buildVersionDiff';
import { SplitDiffTable } from './diff/SplitDiffTable';
import { UnifiedDiffTable } from './diff/UnifiedDiffTable';
import { FieldDiffTable } from './diff/FieldDiffTable';
import { SetDiffList } from './diff/SetDiffList';
import { useNarrowDiff } from './diff/useNarrowDiff';
import './AgentVersionDiff.css';

export interface AgentVersionDiffProps {
  base: AgentVersion | null;
  target: AgentVersion;
  variant: 'modal' | 'inline';
  /** True when the agent has an online version that could not be resolved any more. */
  baselineUnavailable?: boolean;
  sdlcNameOf?: (sdlcId: number) => string | undefined;
  headerExtra?: ReactNode;
}

const GROUP_TITLE_KEY = {
  soul: 'diff.group.soul',
  agentMd: 'diff.group.agentMd',
  config: 'diff.group.config',
  repoPerms: 'diff.group.repoPerms',
  skills: 'diff.group.skills',
  memoryRefs: 'diff.group.memoryRefs',
  environmentVariables: 'diff.group.environmentVariables',
} as const;

const GROUP_SUBTITLE_KEY = {
  soul: 'diff.group.soulFile',
  agentMd: 'diff.group.agentMdFile',
} as const;

type GroupSubtitleKey = (typeof GROUP_SUBTITLE_KEY)[keyof typeof GROUP_SUBTITLE_KEY];

function subtitleKeyOf(key: DiffGroupKey): GroupSubtitleKey | undefined {
  if (key === 'soul' || key === 'agentMd') return GROUP_SUBTITLE_KEY[key];
  return undefined;
}

const CONTEXT_OPTIONS = [3, 5, 10] as const;
const LARGE_DOC_LINES = 3000;
const MODAL_VARIANT = 'modal' as const;
const CONFIG_GROUP_KEY = 'config' as const;
const ENVIRONMENT_GROUP_KEY = 'environmentVariables' as const;
const SET_GROUP_KEYS: ReadonlySet<DiffGroupKey> = new Set<DiffGroupKey>([
  'repoPerms', 'skills', 'memoryRefs', 'environmentVariables',
]);

function GroupStats({ group, firstVersion }: { group: DiffGroup; firstVersion: boolean }) {
  const { t } = useTranslation('agent');
  const { added, removed, modified } = group.stats;
  if (added + removed + modified === 0) {
    return <span className="awdiff-stat-none">{t('diff.group.noChange')}</span>;
  }
  const addedText = added > 0 ? `+${added}` : null;
  const removedText = !firstVersion && removed > 0 ? `−${removed}` : null;
  const modifiedText = modified > 0
    ? (group.key === CONFIG_GROUP_KEY
      ? t('diff.stat.changedFields', { fields: modified })
      : t('diff.stat.adjusted', { items: modified }))
    : null;
  return (
    <span className="awdiff-stat">
      {addedText && <span className="awdiff-stat-add">{addedText}</span>}
      {removedText && <span className="awdiff-stat-del">{removedText}</span>}
      {modifiedText && <span className="awdiff-stat-adj">{modifiedText}</span>}
    </span>
  );
}

function TextDiffSection({
  diffKey,
  diff,
  firstVersion,
  unified,
  largeDoc,
  baseLabel,
  targetLabel,
  targetVersionNo,
}: {
  diffKey: string;
  diff: LineDiffResult;
  firstVersion: boolean;
  unified: boolean;
  largeDoc: boolean;
  baseLabel: string;
  targetLabel: string;
  targetVersionNo: number;
}) {
  const { t } = useTranslation('agent');
  const [collapsed, setCollapsed] = useState(true);
  const [contextLines, setContextLines] = useState<number>(largeDoc ? 0 : 3);
  const [expandedFolds, setExpandedFolds] = useState<ReadonlySet<number>>(() => new Set());
  const [activeBlock, setActiveBlock] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);

  const items = useMemo(
    () => buildRenderItems(diff, { collapsed, contextLines, expandedFolds }),
    [diff, collapsed, contextLines, expandedFolds],
  );
  const blockIndexByRow = useMemo(() => {
    const map = new Map<number, number>();
    diff.blocks.forEach((block, index) => map.set(block.start, index));
    return map;
  }, [diff]);

  if (firstVersion) {
    return (
      <div className="awdiff-text" ref={containerRef}>
        <div className="awdiff-doc-head">{t('diff.firstVersionDoc', { version: targetVersionNo })}</div>
        <UnifiedDiffTable
          items={diff.rows.map((row, rowIndex) => ({ type: 'row', row, rowIndex }))}
          targetLabel={targetLabel}
          blockIndexByRow={new Map()}
          onExpandFold={() => undefined}
        />
      </div>
    );
  }

  const current = diff.blocks.length === 0 ? 0 : Math.min(activeBlock, diff.blocks.length - 1);
  const counterText = diff.blocks.length === 0 ? '0 / 0' : `${current + 1} / ${diff.blocks.length}`;
  const gotoBlock = (next: number) => {
    const bounded = Math.max(0, Math.min(next, diff.blocks.length - 1));
    setActiveBlock(bounded);
    const anchor = containerRef.current?.querySelector(`[data-block-index="${bounded}"]`);
    (anchor as HTMLElement | null)?.scrollIntoView?.({ block: 'center' });
  };
  const expandFold = (foldStart: number) => {
    setExpandedFolds((previous) => {
      const next = new Set(previous);
      next.add(foldStart);
      return next;
    });
  };

  return (
    <div className="awdiff-text" ref={containerRef} data-testid={`awdiff-text-${diffKey}`}>
      <div className="awdiff-toolbar awdiff-toolbar-wrap">
        <label className="awdiff-switch">
          <Switch size="small" checked={collapsed} onChange={setCollapsed}
            aria-label={t('diff.action.collapseUnchanged')} />
          <span>{t('diff.action.collapseUnchanged')}</span>
        </label>
        <span className="awdiff-toolbar-label">{t('diff.toolbar.context')}</span>
        <Select
          size="small"
          className="awdiff-context-select"
          value={contextLines}
          onChange={setContextLines}
          options={(largeDoc ? [0, ...CONTEXT_OPTIONS] : [...CONTEXT_OPTIONS]).map((value) => ({
            value,
            label: t('diff.toolbar.contextLines', { lines: value }),
          }))}
          aria-label={t('diff.toolbar.context')}
        />
        <span className="awdiff-toolbar-grow" />
        <span className="awdiff-nav">
          <Button size="small" disabled={diff.blocks.length === 0 || current === 0}
            onClick={() => gotoBlock(current - 1)}>
            {t('diff.action.prevChange')}
          </Button>
          <span className="awdiff-nav-count" data-testid={`awdiff-nav-count-${diffKey}`}>
            {counterText}
          </span>
          <Button size="small" disabled={diff.blocks.length === 0 || current >= diff.blocks.length - 1}
            onClick={() => gotoBlock(current + 1)}>
            {t('diff.action.nextChange')}
          </Button>
        </span>
        <Button size="small" type="link" onClick={() => setCollapsed(false)}>
          {t('diff.action.expandAll')}
        </Button>
      </div>
      {unified ? (
        <UnifiedDiffTable
          items={items}
          targetLabel={targetLabel}
          blockIndexByRow={blockIndexByRow}
          onExpandFold={expandFold}
        />
      ) : (
        <SplitDiffTable
          items={items}
          baseLabel={baseLabel}
          targetLabel={targetLabel}
          blockIndexByRow={blockIndexByRow}
          onExpandFold={expandFold}
        />
      )}
    </div>
  );
}

export function AgentVersionDiff({
  base,
  target,
  variant,
  baselineUnavailable = false,
  sdlcNameOf,
  headerExtra,
}: AgentVersionDiffProps) {
  const { t } = useTranslation('agent');
  const narrow = useNarrowDiff();
  const [manualUnified, setManualUnified] = useState<boolean | null>(null);
  const unified = manualUnified ?? narrow;
  const model: VersionDiffModel = useMemo(() => buildVersionDiff(base, target), [base, target]);
  const sectionRefs = useRef(new Map<DiffGroupKey, HTMLElement>());

  const baseLabel = base ? t('diff.column.baseWithVersion', { version: base.versionNo }) : '';
  const targetLabel = t('diff.column.targetWithVersion', { version: target.versionNo });
  const submitter = target.creatorName || (target.creatorId ? `#${target.creatorId}` : '-');
  const allSame = !model.firstVersion
    && model.groups.every((group) => group.stats.added + group.stats.removed + group.stats.modified === 0);
  const largeDoc = (target.businessBackground ?? '').split('\n').length > LARGE_DOC_LINES
    || (target.responsibilities ?? '').split('\n').length > LARGE_DOC_LINES;

  const scrollToGroup = (key: DiffGroupKey) => {
    sectionRefs.current.get(key)?.scrollIntoView?.({ block: 'start' });
  };

  return (
    <div className={`awdiff awdiff-${variant}`} data-testid="agent-version-diff">
      <div className="awdiff-head">
        <div className="awdiff-meta">
          <span className="awdiff-chain">
            {base
              ? <span className="awdiff-ver awdiff-ver-base">{t('diff.versionShort', { version: base.versionNo })}</span>
              : <span className="awdiff-ver awdiff-ver-none">{t('diff.noBaseline')}</span>}
            <span className="awdiff-arrow" aria-hidden />
            <span className="awdiff-ver awdiff-ver-target">{t('diff.versionShort', { version: target.versionNo })}</span>
          </span>
          <span>{base && !baselineUnavailable ? t('diff.baselineOnline') : t('diff.baselineNone')}</span>
          <span>{t('diff.submitter', { name: submitter })}</span>
          <span>{t('diff.submittedAt', { time: formatDateTime(target.gmtCreate) })}</span>
          <span className="awdiff-meta-grow" />
          <Button size="small" onClick={() => setManualUnified(!unified)}>
            {unified ? t('diff.layout.split') : t('diff.layout.unified')}
          </Button>
          {headerExtra}
        </div>
        <div className="awdiff-chips">
          {model.groups.map((group) => (
            <button
              type="button"
              key={group.key}
              className={group.stats.added + group.stats.removed + group.stats.modified === 0 ? 'awdiff-chip awdiff-chip-empty' : 'awdiff-chip'}
              onClick={() => scrollToGroup(group.key)}
            >
              <b>{t(GROUP_TITLE_KEY[group.key])}</b>
              <GroupStats group={group} firstVersion={model.firstVersion} />
            </button>
          ))}
        </div>
        {target.submitNote && (
          <div className="awdiff-commit-note">
            <span className="awdiff-commit-label">{t('diff.submitNote')}</span>
            <span className="awdiff-commit-text">{target.submitNote}</span>
          </div>
        )}
      </div>

      <div className="awdiff-body">
        {model.firstVersion && !baselineUnavailable && (
          <div className="awdiff-notice awdiff-notice-info">{t('diff.notice.firstVersion')}</div>
        )}
        {baselineUnavailable && (
          <div className="awdiff-notice">{t('diff.notice.baselineUnavailable')}</div>
        )}
        {!model.firstVersion && !baselineUnavailable && variant === MODAL_VARIANT && (
          <div className="awdiff-notice">
            {t('diff.notice.compareBase', { base: base ? `v${base.versionNo}` : '-', target: `v${target.versionNo}` })}
          </div>
        )}
        {allSame && <div className="awdiff-notice awdiff-notice-info">{t('diff.notice.allSame')}</div>}
        {largeDoc && !model.firstVersion && (
          <div className="awdiff-notice awdiff-notice-info">{t('diff.notice.largeDoc')}</div>
        )}

        {model.groups.map((group) => {
          const subtitleKey = subtitleKeyOf(group.key);
          const isEmpty = group.stats.added + group.stats.removed + group.stats.modified === 0;
          return (
            <section
              key={group.key}
              className={isEmpty ? 'awdiff-section awdiff-section-empty' : 'awdiff-section'}
              ref={(element) => {
                if (element) sectionRefs.current.set(group.key, element);
              }}
              data-testid={`awdiff-section-${group.key}`}
            >
              <div className="awdiff-section-head">
                <h3>
                  {t(GROUP_TITLE_KEY[group.key])}
                  {subtitleKey && <span className="awdiff-file">{t(subtitleKey)}</span>}
                </h3>
                <GroupStats group={group} firstVersion={model.firstVersion} />
              </div>
              <div className="awdiff-section-body">
                {group.text && (
                  (group.text.rows.length === 0)
                    ? <div className="awdiff-empty">{t('diff.empty.noContent')}</div>
                    : (
                      <TextDiffSection
                        diffKey={group.key}
                        diff={group.text}
                        firstVersion={model.firstVersion}
                        unified={unified}
                        largeDoc={largeDoc}
                        baseLabel={baseLabel}
                        targetLabel={targetLabel}
                        targetVersionNo={target.versionNo}
                      />
                    )
                )}
                {group.config && (
                  <FieldDiffTable
                    fields={group.config}
                    base={base}
                    target={target}
                    firstVersion={model.firstVersion}
                    sdlcNameOf={sdlcNameOf}
                  />
                )}
                {group.key === ENVIRONMENT_GROUP_KEY && !model.firstVersion && (
                  <div className="awdiff-toolbar awdiff-toolbar-note">{t('diff.notice.envMasked')}</div>
                )}
                {SET_GROUP_KEYS.has(group.key) && (
                  <SetDiffList group={group} />
                )}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
