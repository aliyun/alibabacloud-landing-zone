import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Empty, Segmented, Select, Space, Spin, Typography, message } from 'antd';
import { CheckOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSquadOptions } from '@/features/squad/SquadFilterBar';
import { ApiError } from '@/shared/types/common';
import {
  generateSquadWorkflow,
  getSquadWorkflow,
  WORKFLOW_STALE_INPUT_CODE,
  type SquadWorkflowView,
} from './api';
import { WorkflowGraphCanvas, type WorkflowSelection, type WorkflowViewMode } from './WorkflowGraphCanvas';
import { WorkflowDetails, WorkflowFindings } from './WorkflowDetails';
import { formatDateTime } from '@/shared/i18n/format';

export const WORKFLOW_POLL_INTERVAL = 3000;
export const WORKFLOW_SLOW_POLL_INTERVAL = 10000;
export const WORKFLOW_FAST_POLL_WINDOW_MS = 30000;

function isActive(status: string | undefined): boolean {
  return status === 'GENERATING' || status === 'REPAIRING';
}

/**
 * Analysis starts with rapid polling so a fast result shows up immediately, then backs off so a
 * long Chief run does not keep hitting the server every three seconds.
 */
export function workflowPollInterval(active: boolean, elapsedMs: number): number | false {
  if (!active) return false;
  return elapsedMs < WORKFLOW_FAST_POLL_WINDOW_MS ? WORKFLOW_POLL_INTERVAL : WORKFLOW_SLOW_POLL_INTERVAL;
}

export function SquadWorkflowPanel() {
  const { t } = useTranslation('sdlc');
  const queryClient = useQueryClient();
  const { options, isLoading: squadsLoading } = useSquadOptions();
  const [squadId, setSquadId] = useState<number | null>(null);
  const [mode, setMode] = useState<WorkflowViewMode>('main');
  const [selection, setSelection] = useState<WorkflowSelection | null>(null);
  const activeSinceRef = useRef<number | null>(null);

  const queryKey = ['squad-workflow', squadId];

  const workflowQuery = useQuery({
    queryKey,
    queryFn: () => getSquadWorkflow(squadId as number),
    enabled: squadId != null,
    refetchInterval: (query) => {
      const active = isActive(query.state.data?.status);
      const since = activeSinceRef.current;
      return workflowPollInterval(active, since == null ? 0 : Date.now() - since);
    },
  });

  const view = workflowQuery.data;
  const hasReadyGraph = view?.status === 'READY' && !!view.graph;

  useEffect(() => {
    setSelection(null);
    setMode('main');
    activeSinceRef.current = null;
  }, [squadId]);

  useEffect(() => {
    if (isActive(view?.status)) {
      if (activeSinceRef.current == null) activeSinceRef.current = Date.now();
    } else {
      activeSinceRef.current = null;
    }
  }, [squadId, view?.status]);

  const generateMutation = useMutation({
    mutationFn: (retry: boolean) => generateSquadWorkflow(squadId as number, {
      expectedInputHash: view?.inputHash ?? null,
      retry,
    }),
    onSuccess: (next) => {
      if (squadId != null) queryClient.setQueryData(['squad-workflow', squadId], next);
    },
    onError: (error: unknown) => {
      if (error instanceof ApiError && error.code === WORKFLOW_STALE_INPUT_CODE) {
        if (error.data && squadId != null) {
          queryClient.setQueryData(['squad-workflow', squadId], error.data as SquadWorkflowView);
        }
        message.info(t('workflow.stale'));
        return;
      }
      message.error(error instanceof Error ? error.message : t('workflow.generationFailed'));
    },
  });

  const generate = (retry: boolean) => {
    if (squadId == null) return;
    generateMutation.mutate(retry);
  };

  const content = () => {
    if (squadId == null) {
      return (
        <Card className="aw-content-card">
          <Empty description={t('workflow.selectHint')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </Card>
      );
    }
    if (workflowQuery.isLoading) {
      return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
    }
    if (workflowQuery.isError) {
      const error = workflowQuery.error;
      return (
        <Alert
          type="error"
          showIcon
          message={error instanceof Error ? error.message : t('workflow.generationFailed')}
          action={<Button size="small" onClick={() => void workflowQuery.refetch()}>{t('workflow.refresh')}</Button>}
        />
      );
    }
    if (!view) {
      return null;
    }
    if (view.status === 'EMPTY') {
      return (
        <Card className="aw-content-card">
          <Empty description={t('workflow.empty')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        </Card>
      );
    }
    if (view.status === 'MISSING') {
      return (
        <Card className="aw-content-card">
          <Empty
            description={(
              <Space direction="vertical" size={4}>
                <Typography.Text strong>{t('workflow.missingTitle')}</Typography.Text>
                <Typography.Text type="secondary">{t('workflow.missingHint')}</Typography.Text>
              </Space>
            )}
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          >
            <Button type="primary" loading={generateMutation.isPending} onClick={() => generate(false)}>
              {t('workflow.generate')}
            </Button>
          </Empty>
        </Card>
      );
    }
    if (isActive(view.status)) {
      const repairing = view.status === 'REPAIRING';
      return (
        <Card className="aw-content-card">
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <Spin size="large" />
            <div style={{ marginTop: 16 }}>
              {repairing ? t('workflow.repairing') : t('workflow.generating')}
            </div>
            <Typography.Text type="secondary">{t('workflow.generatingHint')}</Typography.Text>
          </div>
        </Card>
      );
    }
    if (view.status === 'FAILED') {
      return (
        <Alert
          type="error"
          showIcon
          message={view.error?.message || t('workflow.failed')}
          description={view.error?.code}
          action={(
            <Space>
              {view.error?.retryable !== false && (
                <Button
                  type="primary"
                  size="small"
                  loading={generateMutation.isPending}
                  onClick={() => generate(true)}
                >
                  {t('workflow.retry')}
                </Button>
              )}
              <Button size="small" onClick={() => void workflowQuery.refetch()}>{t('workflow.refresh')}</Button>
            </Space>
          )}
        />
      );
    }
    if (!view.graph) {
      return (
        <Alert
          type="warning"
          showIcon
          message={t('workflow.graphEmpty')}
          action={<Button size="small" onClick={() => void workflowQuery.refetch()}>{t('workflow.refresh')}</Button>}
        />
      );
    }
    const nodes = view.graph.workflow.nodes;
    return (
      <div className="aw-workflow-layout">
        <section className="aw-workflow-main">
          <div className="aw-workflow-graph-card">
            <div className="aw-workflow-graph-head">
              <div className="aw-workflow-graph-heading">
                <span className="aw-workflow-graph-title">{t('workflow.graphTitle')}</span>
                <span className="aw-workflow-graph-count">
                  {t('workflow.memberCount', { count: nodes.filter(node => node.kind === 'MEMBER').length })}
                  {t('workflow.referenceSeparator')}{t('workflow.transitionCount', { count: view.graph.workflow.edges.length })}
                </span>
              </div>
              <Segmented aria-label={t('workflow.viewMode')} value={mode}
                onChange={(next) => { setMode(next as WorkflowViewMode); setSelection(null); }}
                options={[{ value: 'main', label: t('workflow.mainPath') }, { value: 'all', label: t('workflow.allPaths') }]} />
            </div>
            <WorkflowGraphCanvas graph={view.graph} mode={mode} onSelect={setSelection} selection={selection} />
            <div className="aw-workflow-footer">
              <div className="aw-workflow-legend" aria-label={t('workflow.legend')}>
                <span><i />{t('workflow.kinds.HANDOFF')}</span>
                <span><i className="aw-workflow-rework" />{t('workflow.kinds.REWORK')}</span>
                <span><i className="aw-workflow-escalation" />{t('workflow.humanIntervention')}</span>
              </div>
              <span>{t('workflow.graphHint')}</span>
            </div>
          </div>
          <WorkflowFindings graph={view.graph} />
        </section>
        <aside className="aw-workflow-side" aria-label={t('workflow.details')}>
          <WorkflowDetails graph={view.graph} selection={selection} onSelect={setSelection} />
        </aside>
      </div>
    );
  };

  return (
    <div data-testid="squad-workflow-panel" className="aw-workflow-panel">
      <div className="aw-workflow-toolbar">
      <div className="aw-workflow-squad-select">
        <label>{t('workflow.squadLabel')}</label>
        <Select
          showSearch
          optionFilterProp="label"
          loading={squadsLoading}
          options={options}
          value={squadId}
          onChange={(next: number) => setSquadId(next)}
          placeholder={t('workflow.selectSquad')}
          aria-label={t('workflow.selectSquad')}
          style={{ minWidth: 260 }}
        />
      </div>
      {hasReadyGraph && (
        <div className="aw-workflow-snapshot">
          <span className="aw-workflow-snapshot-status"><CheckOutlined aria-hidden="true" /> {t('workflow.currentSnapshot')}</span>
          {view.generatedAt && <span className="aw-workflow-snapshot-time">{t('workflow.generatedAt', { time: formatDateTime(view.generatedAt) })}</span>}
          <Button aria-label={t('workflow.refresh')} icon={<ReloadOutlined aria-hidden="true" />} onClick={() => void workflowQuery.refetch()}>{t('workflow.refresh')}</Button>
        </div>
      )}
      </div>
      {content()}
      {hasReadyGraph && <p className="aw-workflow-note">{t('workflow.staticNote')}</p>}
    </div>
  );
}
