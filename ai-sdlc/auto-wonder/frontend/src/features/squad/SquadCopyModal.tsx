import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { Alert, Button, Checkbox, Empty, Input, Modal, Pagination, Space, Spin, Tag } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ApiError } from '@/shared/types/common';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { checkSquadCopy, copySquadToWorkspaces, listSquadCopyTargets } from './api';
import type { Squad, SquadCopyTarget, SquadCopyTargetResult } from './api';

const TARGET_PAGE_SIZE = 10;
const KEYWORD_DEBOUNCE_MS = 300;

// 每个“用户明确的复制意图”绑定一个请求标识：同一意图重发（响应丢失后再次确认）由服务端幂等回放，
// 关闭弹窗后重新发起才是新操作。
function newRequestId(): string {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `squad-copy-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

interface SquadCopyModalProps {
  squad: Squad | null;
  onClose: () => void;
}

export function SquadCopyModal({ squad, onClose }: SquadCopyModalProps) {
  const { t } = useTranslation('squad');
  const squadId = squad?.id ?? null;
  const open = squadId !== null;

  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  // 已选目标独立于当前页与搜索词保存，翻页/改词不会丢选择。
  const [selected, setSelected] = useState<Map<number, string>>(new Map());
  // 第二次确认的目标集合与请求标识；非空即确认弹窗打开。
  const [pending, setPending] = useState<{ requestId: string; ids: number[] } | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [results, setResults] = useState<SquadCopyTargetResult[] | null>(null);
  const [retryIds, setRetryIds] = useState<number[]>([]);

  useEffect(() => {
    setKeyword('');
    setPage(1);
    setSelected(new Map());
    setPending(null);
    setSubmitError(null);
    setResults(null);
    setRetryIds([]);
  }, [squadId]);

  const debouncedKeyword = useDebouncedValue(keyword, KEYWORD_DEBOUNCE_MS);

  const checkQuery = useQuery({
    queryKey: ['squad-copy-check', squadId],
    queryFn: () => checkSquadCopy(squadId as number),
    enabled: open,
  });
  const eligible = checkQuery.data?.eligible === true;
  const missingOnlineVersions = checkQuery.data?.missingOnlineVersions ?? [];
  const missingDependencies = checkQuery.data?.missingDependencies ?? [];

  // 服务端依赖引用形如 agent#42 / skill#7 / plugin#name，这里转成可读文案；未知种类原样展示。
  const dependencyLabel = (reference: string) => {
    const separator = reference.indexOf('#');
    const kind = separator < 0 ? '' : reference.slice(0, separator);
    const value = separator < 0 ? reference : reference.slice(separator + 1);
    switch (kind) {
      case 'agent':
        return t('copyDependencyAgent', { value });
      case 'skill':
        return t('copyDependencySkill', { value });
      case 'plugin':
        return t('copyDependencyPlugin', { value });
      case 'environment_variable':
        return t('copyDependencyEnvironmentVariable', { value });
      case 'sdlc':
        return t('copyDependencySdlc', { value });
      default:
        return reference;
    }
  };

  const targetsQuery = useQuery({
    queryKey: ['squad-copy-targets', squadId, debouncedKeyword, page],
    queryFn: () => listSquadCopyTargets(squadId as number, {
      keyword: debouncedKeyword,
      pageNum: page,
      pageSize: TARGET_PAGE_SIZE,
    }),
    enabled: open && eligible,
  });

  const copyMut = useMutation({
    mutationFn: (payload: { requestId: string; targetWorkspaceIds: number[] }) =>
      copySquadToWorkspaces(squadId as number, payload),
    onSuccess: (resp) => {
      setResults((prev) => {
        if (!prev) return resp.results;
        // 重试只覆盖本次重试的目标，成功副本的结果保持原样。
        const fresh = new Map(resp.results.map((item) => [item.workspaceId, item]));
        return prev.map((item) => fresh.get(item.workspaceId) ?? item);
      });
      setRetryIds([]);
      setPending(null);
      setSubmitError(null);
    },
    onError: (error) => {
      setSubmitError(error instanceof ApiError ? error.message : t('copySubmitFailed'));
    },
  });

  const targets = targetsQuery.data?.list ?? [];
  const total = targetsQuery.data?.total ?? 0;

  const toggleSelection = (id: number, name: string, checked: boolean) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (checked) {
        next.set(id, name);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const nameById = useMemo(() => {
    const names = new Map<number, string>(selected);
    results?.forEach((item) => names.set(item.workspaceId, item.workspaceName));
    return names;
  }, [selected, results]);

  const pendingNames = pending
    ? pending.ids.map((id) => nameById.get(id) ?? i18n.t('squadFallback', { ns: 'squad', id }))
    : [];
  const failedResults = results?.filter((item) => !item.success) ?? [];

  const openConfirm = (ids: number[]) => {
    if (ids.length === 0 || copyMut.isPending) return;
    setSubmitError(null);
    setPending({ requestId: newRequestId(), ids });
  };

  const submitCopy = () => {
    if (!pending || copyMut.isPending) return;
    setSubmitError(null);
    copyMut.mutate({ requestId: pending.requestId, targetWorkspaceIds: pending.ids });
  };

  return (
    <>
      <Modal
        closable={{ 'aria-label': t('close'), closeIcon: <CloseOutlined aria-hidden="true" /> }}
        title={results ? t('copyResultTitle') : t('copyToWorkspace')}
        open={open}
        onCancel={onClose}
        footer={null}
        width={640}
      >
        {checkQuery.isLoading ? (
          <div style={{ textAlign: 'center', padding: 32 }}>
            <Spin aria-label={t('loading')} />
          </div>
        ) : checkQuery.isError ? (
          <Alert
            type="error"
            showIcon
            message={t('copyCheckFailed')}
            action={(
              <Button size="small" onClick={() => checkQuery.refetch()}>
                {t('copyRetryCheck')}
              </Button>
            )}
          />
        ) : !eligible ? (
          <div data-testid="copy-blocked">
            {missingOnlineVersions.length > 0 ? (
              <>
                <Alert type="error" showIcon message={t('copyIneligible')} />
                <ul data-testid="copy-blocked-list">
                  {missingOnlineVersions.map((item) => (
                    <li key={item.agentId}>{item.agentName}</li>
                  ))}
                </ul>
                <div style={{ color: 'var(--aw-muted)' }}>{t('copyIneligibleHint')}</div>
              </>
            ) : null}
            {missingDependencies.length > 0 ? (
              <div
                data-testid="copy-blocked-dependencies"
                style={{ marginTop: missingOnlineVersions.length > 0 ? 12 : 0 }}
              >
                <Alert type="error" showIcon message={t('copyDependenciesMissing')} />
                <ul data-testid="copy-blocked-dependencies-list">
                  {missingDependencies.map((reference) => (
                    <li key={reference}>{dependencyLabel(reference)}</li>
                  ))}
                </ul>
                <div style={{ color: 'var(--aw-muted)' }}>{t('copyDependenciesMissingHint')}</div>
              </div>
            ) : null}
          </div>
        ) : results ? (
          <div>
            <div style={{ display: 'grid', gap: 10 }}>
              {results.map((item) => (
                <div
                  key={item.workspaceId}
                  data-testid={`copy-result-${item.workspaceId}`}
                  style={{
                    border: '1px solid var(--aw-border)',
                    borderRadius: 8,
                    padding: '10px 12px',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 10,
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 700, color: 'var(--aw-text)' }}>{item.workspaceName}</div>
                    {item.success ? (
                      <a href={`/agents?tab=squads&squadId=${item.squadId}&workspaceId=${item.workspaceId}`}>
                        {t('copyResultSquadLink', { id: item.squadId ?? '' })}
                      </a>
                    ) : (
                      <div style={{ color: 'var(--aw-error)', fontSize: 12 }}>{item.message || item.errorCode}</div>
                    )}
                  </div>
                  {item.success ? (
                    <Tag color="green">{t('copyResultSuccess')}</Tag>
                  ) : (
                    <Space size={6}>
                      <Tag color="red">{t('copyResultFailed')}</Tag>
                      <Checkbox
                        aria-label={t('copyRetrySelect', { name: item.workspaceName })}
                        checked={retryIds.includes(item.workspaceId)}
                        onChange={(event) => setRetryIds((prev) => (
                          event.target.checked
                            ? [...prev, item.workspaceId]
                            : prev.filter((id) => id !== item.workspaceId)
                        ))}
                      />
                    </Space>
                  )}
                </div>
              ))}
            </div>

            <div style={{ marginTop: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
              <span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>
                {failedResults.length > 0 ? t('copyRetryHint') : null}
              </span>
              <Space>
                {failedResults.length > 0 ? (
                  <Button disabled={retryIds.length === 0} onClick={() => openConfirm(retryIds)}>
                    {t('copyRetrySelected', { count: retryIds.length })}
                  </Button>
                ) : null}
                <Button type="primary" onClick={onClose}>{t('copyDone')}</Button>
              </Space>
            </div>
          </div>
        ) : (
          <div>
            <Input
              aria-label={t('copyTargetsSearch')}
              placeholder={t('copyTargetsSearch')}
              allowClear
              value={keyword}
              onChange={(event) => {
                setKeyword(event.target.value);
                // 改搜索词后停留在旧页码会落在空页上。
                setPage(1);
              }}
              style={{ maxWidth: 320 }}
            />
            <div style={{ marginTop: 8, color: 'var(--aw-muted)' }}>{t('copyTargetsTotal', { count: total })}</div>

            {targetsQuery.isLoading ? (
              <div style={{ textAlign: 'center', padding: 32 }}>
                <Spin aria-label={t('loading')} />
              </div>
            ) : targets.length === 0 ? (
              <Empty
                description={debouncedKeyword.trim()
                  ? t('copyTargetsNoMatch', { keyword: debouncedKeyword.trim() })
                  : t('copyTargetsEmpty')}
              />
            ) : (
              <div style={{ marginTop: 12, display: 'grid', gap: 8 }}>
                {targets.map((target: SquadCopyTarget) => (
                  <div
                    key={target.id}
                    style={{
                      border: '1px solid var(--aw-border)',
                      borderRadius: 8,
                      padding: '10px 12px',
                      display: 'flex',
                      gap: 10,
                      alignItems: 'flex-start',
                    }}
                  >
                    <Checkbox
                      aria-label={target.name}
                      checked={selected.has(target.id)}
                      onChange={(event) => toggleSelection(target.id, target.name, event.target.checked)}
                    />
                    {/* 点击行文本同样切换选择；antd Checkbox 自带 label，行容器不能再用 label 包裹。 */}
                    <div
                      style={{ cursor: 'pointer', flex: 1, minWidth: 0 }}
                      onClick={() => toggleSelection(target.id, target.name, !selected.has(target.id))}
                    >
                      <div style={{ fontWeight: 700, color: 'var(--aw-text)' }}>{target.name}</div>
                      <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>
                        {target.description || t('noDescription')}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {total > TARGET_PAGE_SIZE ? (
              <div style={{ marginTop: 12, textAlign: 'right' }}>
                <Pagination
                  current={page}
                  pageSize={TARGET_PAGE_SIZE}
                  total={total}
                  showSizeChanger={false}
                  onChange={setPage}
                />
              </div>
            ) : null}

            {selected.size > 0 ? (
              <div data-testid="copy-selected-summary" style={{ marginTop: 12 }}>
                <div style={{ color: 'var(--aw-muted)', marginBottom: 6 }}>
                  {t('copySelectedCount', { count: selected.size })}
                </div>
                <Space size={[6, 6]} wrap>
                  {Array.from(selected.entries()).map(([id, name]) => (
                    <Tag key={id} closable onClose={() => toggleSelection(id, name, false)}>{name}</Tag>
                  ))}
                </Space>
              </div>
            ) : null}

            <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <Button onClick={onClose}>{t('cancel')}</Button>
              <Button type="primary" disabled={selected.size === 0} onClick={() => openConfirm(Array.from(selected.keys()))}>
                {t('copyConfirmAction')}
              </Button>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        closable={!copyMut.isPending}
        title={t('copyConfirmTitle')}
        open={pending !== null}
        okText={t('copyConfirmAction')}
        cancelText={t('cancel')}
        confirmLoading={copyMut.isPending}
        cancelButtonProps={{ disabled: copyMut.isPending }}
        maskClosable={false}
        onOk={submitCopy}
        onCancel={() => {
          if (copyMut.isPending) return;
          setPending(null);
          setSubmitError(null);
        }}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <div>{t('copyConfirmSourceLine', { name: squad?.name ?? '' })}</div>
          <div>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>{t('copyConfirmTargets')}</div>
            <Space size={[6, 6]} wrap>
              {pendingNames.map((name) => <Tag key={name}>{name}</Tag>)}
            </Space>
          </div>
          <div>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>{t('copyConfirmScope')}</div>
            <ul style={{ margin: 0, paddingLeft: 18, color: 'var(--aw-text)' }}>
              <li>{t('copyConfirmScopeValue')}</li>
              <li>{t('copyConfirmReposExcluded')}</li>
              <li>{t('copyConfirmAgentsOnline')}</li>
              <li>{t('copyConfirmExecutorRestart')}</li>
            </ul>
          </div>
          {copyMut.isPending ? <Spin aria-label={t('copyRunning')} /> : null}
          {submitError ? <Alert type="error" showIcon message={submitError} /> : null}
        </Space>
      </Modal>
    </>
  );
}
