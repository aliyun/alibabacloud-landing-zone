import { i18n } from '@/shared/i18n';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { Trans, useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useState } from 'react';
import { Button, Card, DatePicker, Input, Modal, Segmented, Space, Tag, Tooltip, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { EyeOutlined, SearchOutlined, CloseCircleFilled } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { useUpdateScheduledStart, useWorkitemList } from '@/features/workitem/hooks';
import type { Workitem, WorkitemScheduledPhase } from '@/shared/types/workitem';

type Scope = 'ALL' | 'CREATED';

/** 已触发的工单定时字段已被清空，三个操作都失去意义，因此按钮常驻但置灰并说明原因。 */
function disabledReason(phase?: WorkitemScheduledPhase | null): string {
  return phase === 'DONE' ? i18n.t('scheduledTask:workitems.doneReason') : i18n.t('scheduledTask:workitems.triggeredReason');
}

function formatDate(value?: string | null) {
  return value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : '-';
}

function ScheduledWorkitemActions({ workitem }: { workitem: Workitem }) {
  const { t } = useTranslation(['scheduledTask', 'workitem']);
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
      message.warning(t('workitem:schedule.futureRequired'));
      return;
    }
    run(t('workitem:schedule.editCommand'), () =>
      mutation.mutateAsync({ scheduledStartAt: editValue.toISOString() }).then(() => setEditOpen(false)),
    );
  };

  const button = (label: string, onClick: () => void, danger?: boolean) => (
    <Tooltip title={actionable ? '' : reason}>
      <span>
        <Button
          size="small"
          danger={danger}
          aria-label={t('workitems.actionAria', { action: label, id: workitem.id })}
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
      {button(t('workitems.editTime'), () => {
        setEditValue(workitem.scheduledStartAt ? dayjs(workitem.scheduledStartAt) : dayjs());
        setEditOpen(true);
      })}
      {button(t('workitems.executeNow'), () => run(t('workitem:schedule.executeCommand'), () => mutation.mutateAsync({ executeNow: true })))}
      {button(t('workitem:schedule.cancel'), () => run(t('workitem:schedule.cancelCommand'), () => mutation.mutateAsync({ scheduledStartAt: null })), true)}
      <Button size="small" icon={<EyeOutlined aria-hidden="true" />} aria-label={t('workitems.actionAria', { action: t('actions.view'), id: workitem.id })} onClick={() => navigate(`/workitems/${workitem.id}`)}>
        {t('actions.view')}
      </Button>
      <Modal
        title={t('workitem:schedule.editTitle')}
        open={editOpen}
        onOk={handleEditOk}
        onCancel={() => setEditOpen(false)}
        confirmLoading={mutation.isPending}
        okText={t('actions.save')}
        cancelText={t('actions.cancel')}
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
  const { t } = useTranslation('scheduledTask');
  const SCOPE_OPTIONS: { value: Scope; label: string }[] = [
    { value: 'ALL', label: t('scope.all') },
    { value: 'CREATED', label: t('scope.created') },
  ];
  const PHASE_META: Record<WorkitemScheduledPhase, { label: string; color: string }> = {
    PENDING: { label: t('phase.pending'), color: 'processing' },
    READY: { label: t('phase.ready'), color: 'warning' },
    RUNNING: { label: t('phase.running'), color: 'success' },
    DONE: { label: t('phase.done'), color: 'default' },
  };
  const navigate = useNavigate();
  const [scope, setScope] = useState<Scope>('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = usePageSizePreference('autowonder.scheduledWorkitems.pageSize', [10, 20, 50], 10);
  const { data, isLoading } = useWorkitemList({
    page,
    size: pageSize,
    scheduledStart: 'ALL',
    ...(scope === 'CREATED' ? { mineScope: 'CREATED' } : {}),
    ...(keyword ? { keyword } : {}),
  });
  const items = data?.list ?? [];

  const columns: ColumnsType<Workitem> = [
    {
      title: t('columns.workitem'), align: 'left',
      dataIndex: 'title',
      width: 240,
      render: (title: string, record) => <a onClick={() => navigate(`/workitems/${record.id}`)}>{title}</a>,
    },
    {
      title: t('columns.phase'),
      dataIndex: 'scheduledPhase',
      width: 120,
      render: (value: WorkitemScheduledPhase | null | undefined) =>
        value ? <Tag color={Object.prototype.hasOwnProperty.call(PHASE_META, value) ? PHASE_META[value]?.color : undefined}>{Object.prototype.hasOwnProperty.call(PHASE_META, value) ? PHASE_META[value].label : value}</Tag> : '-',
    },
    { title: t('columns.workitemStatus'), dataIndex: 'statusName', width: 120, render: (value: string | null) => value || '-' },
    { title: t('columns.plannedAt'), width: 180, render: (_, record) => formatDate(record.scheduledStartAt ?? record.scheduledStartTriggeredAt) },
    { title: t('columns.actions'), width: 320, render: (_, record) => <ScheduledWorkitemActions workitem={record} /> },
  ];

  return (
    <Card className="aw-content-card aw-scheduled-list" title={<PageHeading title={t('list.workitemsTitle')} description={<span><Trans t={t} ns="scheduledTask" i18nKey="list.totalWorkitems" count={data?.total ?? items.length} values={{ total: formatNumber(data?.total ?? items.length) }} components={{ number: <span className="aw-heading-number" /> }} /></span>} />}>
      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          aria-label={t('workitems.scopeAria')}
          options={SCOPE_OPTIONS}
          value={scope}
          onChange={(value) => { setScope(value as Scope); setPage(1); }}
        />
        <Input.Search
          enterButton={<Button aria-label={t('actions.search')} icon={<SearchOutlined aria-hidden="true" />} />}
          aria-label={t('workitems.keywordAria')}
          placeholder={t('workitems.searchPlaceholder')}
          allowClear={{ clearIcon: <CloseCircleFilled aria-label={t('actions.clearWorkitemSearch')} /> }}
          style={{ width: 220 }}
          onSearch={(value) => { setKeyword(value); setPage(1); }}
          onChange={(event) => { if (!event.target.value) { setKeyword(''); setPage(1); } }}
        />
      </Space>
      <Table
        scroll={{ x: 980 }}
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={isLoading}
        pagination={{
          current: page,
          pageSize,
          total: data?.total,
          onChange: (nextPage, nextSize) => {
            setPage(nextPage);
            if (nextSize !== pageSize) { setPageSize(nextSize); setPage(1); }
          },
          showSizeChanger: true,
          showTotal: (count) => t('list.totalRows', { count, total: formatNumber(count) }),
        }}
      />
    </Card>
  );
}
