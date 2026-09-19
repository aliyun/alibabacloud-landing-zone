import { useState } from 'react';
import { Button, DatePicker, Input, Modal, Segmented, Space, Table, Tag, Tooltip, message } from 'antd';
import { EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useUpdateScheduledStart, useWorkitemList } from '@/features/workitem/hooks';
import type { Workitem, WorkitemScheduledPhase } from '@/shared/types/workitem';

const PHASE_META: Record<WorkitemScheduledPhase, { label: string; color: string }> = {
  PENDING: { label: '待触发', color: 'processing' },
  READY: { label: '待处理', color: 'warning' },
  RUNNING: { label: '执行中', color: 'success' },
  DONE: { label: '已完成', color: 'default' },
};

type Scope = 'ALL' | 'CREATED';

const SCOPE_OPTIONS: { value: Scope; label: string }[] = [
  { value: 'ALL', label: '查看全部' },
  { value: 'CREATED', label: '我创建的' },
];

const PAGE_SIZE = 20;

/** 已触发的工单定时字段已被清空，三个操作都失去意义，因此按钮常驻但置灰并说明原因。 */
function disabledReason(phase?: WorkitemScheduledPhase | null): string {
  return phase === 'DONE' ? '工单已完成，无法再调整定时' : '定时已触发，无法再调整';
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function ScheduledWorkitemActions({ workitem }: { workitem: Workitem }) {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const mutation = useUpdateScheduledStart(workitem.id);
  const [editOpen, setEditOpen] = useState(false);
  const [editValue, setEditValue] = useState<Dayjs | null>(null);
  const actionable = workitem.scheduledPhase === 'PENDING';
  const reason = actionable ? '' : disabledReason(workitem.scheduledPhase);

  const run = (action: string, call: () => Promise<unknown>) =>
    accessCommand('READ_WRITE', action, async () => {
      try {
        await call();
      } catch {
        // ApiError already surfaced by interceptor
      }
    });

  const handleEditOk = () => {
    if (!editValue || editValue.isBefore(dayjs(), 'minute')) {
      message.warning('计划执行时间必须是将来的时间点');
      return;
    }
    run('调整工单计划执行时间', () =>
      mutation.mutateAsync({ scheduledStartAt: editValue.toISOString() }).then(() => setEditOpen(false)),
    );
  };

  const button = (label: string, onClick: () => void, danger?: boolean) => (
    <Tooltip title={actionable ? '' : reason}>
      <span>
        <Button
          size="small"
          danger={danger}
          aria-label={`${label} #${workitem.id}`}
          disabled={!actionable}
          loading={mutation.isPending}
          onClick={onClick}
        >
          {label}
        </Button>
      </span>
    </Tooltip>
  );

  return (
    <Space size={4}>
      {button('修改时间', () => {
        setEditValue(workitem.scheduledStartAt ? dayjs(workitem.scheduledStartAt) : dayjs());
        setEditOpen(true);
      })}
      {button('立即启动', () => run('立即执行工单', () => mutation.mutateAsync({ executeNow: true })))}
      {button('取消定时', () => run('取消工单定时执行', () => mutation.mutateAsync({ scheduledStartAt: null })), true)}
      <Button size="small" icon={<EyeOutlined />} aria-label={`查看 #${workitem.id}`} onClick={() => navigate(`/workitems/${workitem.id}`)}>
        查看
      </Button>
      <Modal
        title="修改计划执行时间"
        open={editOpen}
        onOk={handleEditOk}
        onCancel={() => setEditOpen(false)}
        confirmLoading={mutation.isPending}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
      >
        <DatePicker
          showTime
          style={{ width: '100%' }}
          value={editValue}
          onChange={(value) => setEditValue(value)}
          disabledDate={(current) => !!current && current.isBefore(dayjs(), 'minute')}
        />
      </Modal>
    </Space>
  );
}

/** 定时任务页 Tab2：聚合展示设置了定时启动的工单，操作全部复用 /api/workitems/{id}/scheduled-start。 */
export function ScheduledWorkitemsPanel() {
  const navigate = useNavigate();
  const [scope, setScope] = useState<Scope>('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const { data, isLoading } = useWorkitemList({
    page,
    size: PAGE_SIZE,
    scheduledStart: 'ALL',
    ...(scope === 'CREATED' ? { mineScope: 'CREATED' } : {}),
    ...(keyword ? { keyword } : {}),
  });
  const items = data?.list ?? [];

  const columns: ColumnsType<Workitem> = [
    {
      title: '工单',
      dataIndex: 'title',
      render: (title: string, record) => <a onClick={() => navigate(`/workitems/${record.id}`)}>{title}</a>,
    },
    {
      title: '当前所处阶段',
      dataIndex: 'scheduledPhase',
      width: 120,
      render: (value: WorkitemScheduledPhase | null | undefined) =>
        value ? <Tag color={PHASE_META[value]?.color}>{PHASE_META[value]?.label ?? value}</Tag> : '-',
    },
    { title: '工单状态', dataIndex: 'statusName', width: 120, render: (value: string | null) => value || '-' },
    { title: '计划执行时间', width: 180, render: (_, record) => formatDate(record.scheduledStartAt ?? record.scheduledStartTriggeredAt) },
    { title: '操作', width: 320, render: (_, record) => <ScheduledWorkitemActions workitem={record} /> },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          aria-label="定时工单范围"
          options={SCOPE_OPTIONS}
          value={scope}
          onChange={(value) => { setScope(value as Scope); setPage(1); }}
        />
        <Input.Search
          aria-label="工单标题筛选"
          placeholder="搜索工单标题"
          allowClear
          style={{ width: 220 }}
          onSearch={(value) => { setKeyword(value); setPage(1); }}
          onChange={(event) => { if (!event.target.value) { setKeyword(''); setPage(1); } }}
        />
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={isLoading}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total: data?.total,
          onChange: (next) => setPage(next),
        }}
      />
    </>
  );
}
