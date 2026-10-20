import { useTranslation } from 'react-i18next';
import { Alert, Button, Empty, List, Modal, Spin } from 'antd';
import type { KpiKey } from './KpiRow';
import type { CompletedWorkitem, RunningTask } from './api';
import { useRunningAll, useTodayCompleted, useWeekCompleted } from './hooks';

interface Props {
  kpiKey: KpiKey | null;
  onClose: () => void;
}

const TITLES = {
  runningDispatches: 'kpi.runningDispatches',
  todayCompletedTasks: 'kpi.todayCompletedTasks',
  weekCompletedTasks: 'kpi.weekCompletedTasks',
} as const satisfies Record<KpiKey, string>;

export default function KpiDetailModal({ kpiKey, onClose }: Props) {
  const { t } = useTranslation(['insights', 'common']);
  const open = kpiKey != null;
  const completedToday = useTodayCompleted(kpiKey === 'todayCompletedTasks');
  const completedWeek = useWeekCompleted(kpiKey === 'weekCompletedTasks');
  const running = useRunningAll(kpiKey === 'runningDispatches');

  const query = kpiKey === 'runningDispatches' ? running : kpiKey === 'todayCompletedTasks' ? completedToday : completedWeek;
  const isLoading =
    (kpiKey === 'todayCompletedTasks' && completedToday.isLoading) ||
    (kpiKey === 'weekCompletedTasks' && completedWeek.isLoading) ||
    (kpiKey === 'runningDispatches' && running.isLoading);

  const completedList: CompletedWorkitem[] =
    kpiKey === 'todayCompletedTasks'
      ? (completedToday.data ?? [])
      : kpiKey === 'weekCompletedTasks'
        ? (completedWeek.data ?? [])
        : [];

  const runningList: RunningTask[] =
    kpiKey === 'runningDispatches' ? (running.data ?? []) : [];

  return (
    <Modal
      title={kpiKey ? t(TITLES[kpiKey]) : ''}
      open={open}
      onCancel={onClose}
      footer={null}
      width={560}
      destroyOnHidden
      styles={{ body: { maxHeight: '65vh', overflowY: 'auto' } }}
    >
      {query.isError ? <Alert type="error" showIcon message={t('kpi.loadFailure')} action={<Button onClick={() => { void query.refetch(); }}>{t('kpi.retry')}</Button>} /> : isLoading ? (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin tip={t('kpi.loading')}><div style={{ minHeight: 40 }} /></Spin>
        </div>
      ) : kpiKey === 'runningDispatches' ? (
        runningList.length === 0 ? (
          <Empty description={t('kpi.emptyRunning')} />
        ) : (
          <List
            dataSource={runningList}
            renderItem={(item) => (
              <List.Item className="insight-detail-row">
                <a href={`/workitems/${item.workitemId}`} style={{ fontWeight: 500, overflowWrap: 'anywhere', minWidth: 0 }}>
                  #{item.workitemId} {item.workitemTitle ?? ''}
                </a>
                <span style={{ marginLeft: 'auto', color: 'var(--aw-muted)', fontSize: 12 }}>
                  {item.agentName} · <span className="insight-realtime-metric">{t('common:duration.minute', { count: item.runningMinutes })}</span>
                </span>
              </List.Item>
            )}
          />
        )
      ) : completedList.length === 0 ? (
        <Empty description={t('kpi.emptyCompleted')} />
      ) : (
        <List
          dataSource={completedList}
          renderItem={(item) => (
            <List.Item className="insight-detail-row">
              <a href={`/workitems/${item.workitemId}`} style={{ fontWeight: 500, overflowWrap: 'anywhere', minWidth: 0 }}>
                #{item.workitemId} {item.title}
              </a>
            </List.Item>
          )}
        />
      )}
    </Modal>
  );
}
