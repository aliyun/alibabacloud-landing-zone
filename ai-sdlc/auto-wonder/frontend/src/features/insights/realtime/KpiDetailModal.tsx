import { Empty, List, Modal, Spin } from 'antd';
import type { KpiKey } from './KpiRow';
import type { CompletedWorkitem, RunningTask } from './api';
import { useRunningAll, useTodayCompleted, useWeekCompleted } from './hooks';

interface Props {
  kpiKey: KpiKey | null;
  onClose: () => void;
}

const TITLES: Record<KpiKey, string> = {
  runningDispatches: '正在运行',
  todayCompletedTasks: '今日完成',
  weekCompletedTasks: '本周完成',
};

export default function KpiDetailModal({ kpiKey, onClose }: Props) {
  const open = kpiKey != null;
  const completedToday = useTodayCompleted(kpiKey === 'todayCompletedTasks');
  const completedWeek = useWeekCompleted(kpiKey === 'weekCompletedTasks');
  const running = useRunningAll(kpiKey === 'runningDispatches');

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
      title={kpiKey ? TITLES[kpiKey] : ''}
      open={open}
      onCancel={onClose}
      footer={null}
      width={560}
      destroyOnClose
    >
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin />
        </div>
      ) : kpiKey === 'runningDispatches' ? (
        runningList.length === 0 ? (
          <Empty description="暂无运行中的工单" />
        ) : (
          <List
            dataSource={runningList}
            renderItem={(item) => (
              <List.Item>
                <a href={`/workitems/${item.workitemId}`} style={{ fontWeight: 500 }}>
                  #{item.workitemId} {item.workitemTitle ?? ''}
                </a>
                <span style={{ marginLeft: 'auto', color: '#999', fontSize: 12 }}>
                  {item.agentName} · {item.runningMinutes} 分钟
                </span>
              </List.Item>
            )}
          />
        )
      ) : completedList.length === 0 ? (
        <Empty description="暂无工单" />
      ) : (
        <List
          dataSource={completedList}
          renderItem={(item) => (
            <List.Item>
              <a href={`/workitems/${item.workitemId}`} style={{ fontWeight: 500 }}>
                #{item.workitemId} {item.title}
              </a>
            </List.Item>
          )}
        />
      )}
    </Modal>
  );
}
