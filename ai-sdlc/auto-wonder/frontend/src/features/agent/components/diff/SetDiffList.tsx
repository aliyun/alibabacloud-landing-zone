import { useTranslation } from 'react-i18next';
import { Tag } from 'antd';
import type { ReactNode } from 'react';
import type {
  DiffGroup,
  EnvVarDiff,
  MemoryDiff,
  RepoPermDiff,
  SetChange,
  SkillDiff,
} from './buildVersionDiff';

interface SetDiffListProps {
  group: DiffGroup;
}

const CHANGE_LABEL_KEY = {
  added: 'diff.set.added',
  removed: 'diff.set.removed',
  modified: 'diff.set.modified',
} as const;

const CHANGE_OP = { added: '+', removed: '−', modified: '~' } as const;

function changeRowClass(change: SetChange): string {
  return `awdiff-set-row awdiff-set-${change}`;
}

function ChangeBadge({ change }: { change: SetChange }) {
  const { t } = useTranslation('agent');
  return <span className="awdiff-set-meta">{t(CHANGE_LABEL_KEY[change])}</span>;
}

function Op({ change }: { change: SetChange }) {
  return <span className={`awdiff-set-op awdiff-set-op-${change}`} aria-hidden>{CHANGE_OP[change]}</span>;
}

function Patterns({ label, patterns }: { label: string; patterns: string[] }) {
  const { t } = useTranslation('agent');
  return (
    <span className="awdiff-set-sub">
      {label}
      {patterns.length === 0
        ? <code>{t('diff.repo.unrestricted')}</code>
        : patterns.map((pattern) => <code key={pattern} className="awdiff-code-chip">{pattern}</code>)}
    </span>
  );
}

function RepoRow({ item }: { item: RepoPermDiff }) {
  const { t } = useTranslation('agent');
  const permissionLabel = (level: string) => {
    const keys = { READ: 'permissions.READ', WRITE: 'permissions.WRITE', ADMIN: 'permissions.ADMIN' } as const;
    const key = keys[level as keyof typeof keys];
    return key ? t(key) : level;
  };
  const permissionChanged = item.change === 'modified' && item.oldPermLevel !== item.permLevel;
  const isModified = item.change === 'modified';
  const tagColor = item.change === 'added' ? 'green' : item.change === 'removed' ? 'red' : 'gold';
  const oldPatterns = item.oldPatterns ?? [];
  return (
    <div className={changeRowClass(item.change)}>
      <Op change={item.change} />
      <div className="awdiff-set-main">
        <div className="awdiff-set-title">
          <span className="awdiff-set-key">{item.repoName}</span>
          {permissionChanged ? (
            <Tag color="gold">
              <span>{permissionLabel(item.oldPermLevel ?? '')}</span>
              <span className="awdiff-arrow" aria-hidden />
              <span>{permissionLabel(item.permLevel)}</span>
            </Tag>
          ) : (
            <Tag>{permissionLabel(item.permLevel)}</Tag>
          )}
          <Tag color={tagColor}>{t(CHANGE_LABEL_KEY[item.change])}</Tag>
        </div>
        {isModified ? (
          <span className="awdiff-set-sub">
            {t('diff.repo.branchPatterns')}
            {oldPatterns.length === 0
              ? <code>{t('diff.repo.unrestricted')}</code>
              : oldPatterns.map((pattern) => <code key={`old-${pattern}`} className="awdiff-code-old">{pattern}</code>)}
            <span className="awdiff-arrow" aria-hidden />
            {item.patterns.length === 0
              ? <code>{t('diff.repo.unrestricted')}</code>
              : item.patterns.map((pattern) => <code key={`new-${pattern}`} className="awdiff-code-new">{pattern}</code>)}
          </span>
        ) : (
          <Patterns label={t('diff.repo.branchPatterns')} patterns={item.patterns} />
        )}
      </div>
      <ChangeBadge change={item.change} />
    </div>
  );
}

function SkillRow({ item }: { item: SkillDiff }) {
  return (
    <div className={changeRowClass(item.change)}>
      <Op change={item.change} />
      <div className="awdiff-set-main">
        <div className="awdiff-set-title">
          <span className="awdiff-set-key">{item.skillName}</span>
        </div>
      </div>
      <ChangeBadge change={item.change} />
    </div>
  );
}

function MemoryRow({ item }: { item: MemoryDiff }) {
  const { t } = useTranslation('agent');
  const titleText = item.title || `#${item.memoryId}`;
  const idText = `#${item.memoryId}`;
  return (
    <div className={changeRowClass(item.change)}>
      <Op change={item.change} />
      <div className="awdiff-set-main">
        <div className="awdiff-set-title">
          <span className="awdiff-set-key">{titleText}</span>
          {item.title && <span className="awdiff-set-id">{idText}</span>}
          <Tag>{item.source}</Tag>
          {item.autoImported && (
            <Tag color="blue">{t('diff.memory.autoImported')}</Tag>
          )}
        </div>
      </div>
      <ChangeBadge change={item.change} />
    </div>
  );
}

function EnvRow({ item }: { item: EnvVarDiff }) {
  const { t } = useTranslation('agent');
  const description = item.description || '-';
  const oldDescription = item.oldDescription || '-';
  const isModified = item.change === 'modified';
  return (
    <div className={changeRowClass(item.change)}>
      <Op change={item.change} />
      <div className="awdiff-set-main">
        <div className="awdiff-set-title">
          <span className="awdiff-set-key">{item.name}</span>
          <Tag color={item.change === 'added' ? 'green' : item.change === 'removed' ? 'red' : 'gold'}>
            {t(CHANGE_LABEL_KEY[item.change])}
          </Tag>
        </div>
        <span className="awdiff-set-sub">
          {t('diff.env.value')}
          <code>{t('diff.env.masked')}</code>
          <span className="awdiff-sep" aria-hidden />
          {t('diff.env.description')}
          {isModified ? (
            <>
              <span className="awdiff-value-old">{oldDescription}</span>
              <span className="awdiff-arrow" aria-hidden />
              <span className="awdiff-value-new">{description}</span>
            </>
          ) : (
            <span className="awdiff-value-plain">{description}</span>
          )}
        </span>
      </div>
      <ChangeBadge change={item.change} />
    </div>
  );
}

export function SetDiffList({ group }: SetDiffListProps) {
  const { t } = useTranslation('agent');
  let rows: ReactNode;
  switch (group.key) {
    case 'repoPerms':
      rows = (group.repos ?? []).map((item) => <RepoRow key={item.key} item={item} />);
      break;
    case 'skills':
      rows = (group.skills ?? []).map((item) => <SkillRow key={item.key} item={item} />);
      break;
    case 'memoryRefs':
      rows = (group.memories ?? []).map((item) => <MemoryRow key={item.key} item={item} />);
      break;
    case 'environmentVariables':
      rows = (group.envVars ?? []).map((item) => <EnvRow key={item.key} item={item} />);
      break;
    default:
      rows = null;
  }
  const { added, removed, modified } = group.stats;
  const emptyText = group.key === 'memoryRefs' ? t('diff.empty.memory') : t('diff.group.noChange');
  if (added + removed + modified === 0) {
    return <div className="awdiff-empty">{emptyText}</div>;
  }
  return <div className="awdiff-set-list">{rows}</div>;
}
