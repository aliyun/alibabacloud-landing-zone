import { useTranslation } from 'react-i18next';
import { formatDateTime } from '@/shared/i18n/format';
import { CloseOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { PageHeading } from '@/shared/ui/PageHeading';
import { AuditJsonBlock } from './AuditJsonBlock';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { useMemo, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Input, Segmented, Select, Space, Typography } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { useQuery } from '@tanstack/react-query';
import { countAuditLogs, listAuditLogs } from './api';
import type { AuditLog, AuditLogFilters } from './api';
import type { ColumnsType } from 'antd/es/table';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { detailText, parseDetail, triggerText } from './auditDetail';

function resolveTimeRange(range: string): Pick<AuditLogFilters, 'startTime' | 'endTime'> {
  if (!range) {
    return {};
  }
  const endTime = new Date();
  const startTime = new Date(endTime);
  if (range === '24h') {
    startTime.setHours(startTime.getHours() - 24);
  } else if (range === '7d') {
    startTime.setDate(startTime.getDate() - 7);
  } else {
    startTime.setDate(startTime.getDate() - 30);
  }
  return {
    startTime: startTime.toISOString(),
    endTime: endTime.toISOString(),
  };
}

export function LegacyAuditLogPage() {
  const { t } = useTranslation('audit');
  const moduleOptions = [
    { label: t('allModules'), value: '' },
    { label: t('workitem'), value: 'WORKITEM' },
    { label: t('agent'), value: 'AGENT' },
    { label: t('skill'), value: 'SKILL' },
    { label: t('dispatch'), value: 'DISPATCH' },
    { label: t('statusTemplate'), value: 'STATUS_TEMPLATE' },
  ];

  const actionOptions = [
    { label: t('allActions'), value: '' },
    { label: t('create'), value: 'CREATE' },
    { label: t('update'), value: 'UPDATE' },
    { label: t('delete'), value: 'DELETE' },
    { label: t('approve'), value: 'APPROVE' },
    { label: t('reject'), value: 'REJECT' },
    { label: t('runtimeEvent'), value: 'RUNTIME_EVENT' },
  ];

  const targetTypeOptions = [
    { label: t('allTargetTypes'), value: '' },
    { label: t('workitem'), value: 'workitem' },
    { label: t('agent'), value: 'agent' },
    { label: t('skill'), value: 'skill' },
    { label: t('dispatch'), value: 'dispatch' },
  ];

  const timeRangeOptions = [
    { label: t('allTime'), value: '' },
    { label: t('last24Hours'), value: '24h' },
    { label: t('last7Days'), value: '7d' },
    { label: t('last30Days'), value: '30d' },
  ];

  const [selected, setSelected] = useState<AuditLog | null>(null);
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('autowonder.auditLogs.pageSize', [10, 20, 50, 100, 200], 10);
  const [draft, setDraft] = useState({
    module: '',
    action: '',
    actorId: '',
    targetType: '',
    targetId: '',
    timeRange: '',
    keyword: '',
  });
  const [filters, setFilters] = useState<Omit<AuditLogFilters, 'page' | 'size'>>({ actorType: 'HUMAN' });

  const queryFilters = useMemo(
    () => ({ page, size, ...filters }),
    [page, size, filters],
  );

  const { data = [], isLoading, isError } = useQuery({
    queryKey: ['audit-logs', queryFilters],
    queryFn: () => listAuditLogs(queryFilters),
  });

  const { data: total = 0 } = useQuery({
    queryKey: ['audit-logs-count', filters],
    queryFn: () => countAuditLogs(filters),
  });

  const actorOptions = [{ value: 'HUMAN', label: t('human') }, { value: 'AGENT', label: t('agent') }, { value: 'SYSTEM', label: t('system') }];
  const columns: ColumnsType<AuditLog> = [
    { title: t('time'), dataIndex: 'gmtCreate', width: 160, render: (value: string) => formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) },
    { title: t('actor'), dataIndex: 'actorName', width: 140, render: (_, record) => {
      const actorType = record.actorType || parseDetail(record).actorType;
      return `${record.actorName || `#${record.actorId}`}${actorType ? ` (${actorOptions.find(option => option.value === actorType)?.label || actorType})` : ''}`;
    } },
    { title: t('action'), dataIndex: 'action', width: 200, render: (action: string, record) => (
      <><div>{actionOptions.find((option) => option.value === action)?.label || action}</div>
        <Typography.Text type="secondary">{moduleOptions.find((option) => option.value === record.module)?.label || record.module}</Typography.Text></>
    ) },
    { title: t('target'), width: 150, render: (_, record) => `${targetTypeOptions.find((option) => option.value === record.targetType)?.label || record.targetType || '—'}${record.targetId != null ? ` #${record.targetId}` : ''}` },
    {
      title: t('summary'), ellipsis: { showTitle: false },
      render: (_, record) => { const text = detailText(record); return <EllipsisText tooltip={text}>{text}</EllipsisText>; },
    },
    { title: '', width: 90, render: (_, record) => <Button type="link" onClick={() => setSelected(record)}>{t('details')}</Button> },
  ];

  const applyFilters = () => {
    setFilters({
      actorType: filters.actorType,
      module: draft.module || undefined,
      action: draft.action || undefined,
      actorId: draft.actorId ? Number(draft.actorId) : undefined,
      targetType: draft.targetType || undefined,
      targetId: draft.targetId ? Number(draft.targetId) : undefined,
      ...resolveTimeRange(draft.timeRange),
      keyword: draft.keyword || undefined,
    });
    setPage(1);
  };

  const resetFilters = () => {
    setDraft({
      module: '',
      action: '',
      actorId: '',
      targetType: '',
      targetId: '',
      timeRange: '',
      keyword: '',
    });
    setFilters({ actorType: 'HUMAN' });
    setPage(1);
  };

  return (
    <Card
      className="aw-content-card"
      title={<PageHeading title={t('title')} description={t('description')} />}
      extra={<Segmented aria-label={t('actorSource')} value={filters.actorType || ''} options={[
        { label: t('humanOperations'), value: 'HUMAN' }, { label: t('agent'), value: 'AGENT' },
        { label: t('system'), value: 'SYSTEM' }, { label: t('allActors'), value: '' },
      ]} onChange={(value) => { setFilters((current) => ({ ...current, actorType: value || undefined })); setPage(1); }} />}
    >
      {isError && <Alert type="error" showIcon icon={<CloseCircleOutlined aria-hidden="true" />} message={t('loadFailed')} style={{ marginBottom: 12 }} />}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 12, marginBottom: 16 }}>
        <Select
          aria-label={t('module')}
          value={draft.module}
          options={moduleOptions}
          onChange={(value) => setDraft((current) => ({ ...current, module: value }))}
          style={{ width: '100%' }}
        />
        <Select
          aria-label={t('action')}
          value={draft.action}
          options={actionOptions}
          onChange={(value) => setDraft((current) => ({ ...current, action: value }))}
          style={{ width: '100%' }}
        />
        <Select
          aria-label={t('targetType')}
          value={draft.targetType}
          options={targetTypeOptions}
          onChange={(value) => setDraft((current) => ({ ...current, targetType: value }))}
          style={{ width: '100%' }}
        />
        <Select
          aria-label={t('timeRange')}
          value={draft.timeRange}
          options={timeRangeOptions}
          onChange={(value) => setDraft((current) => ({ ...current, timeRange: value }))}
          style={{ width: '100%' }}
        />
        <Input
          aria-label={t('actorId')}
          value={draft.actorId}
          placeholder={t('actorPlaceholder')}
          onChange={(event) => setDraft((current) => ({ ...current, actorId: event.target.value }))}
          style={{ width: '100%' }}
        />
        <Input
          aria-label={t('targetId')}
          value={draft.targetId}
          placeholder={t('targetPlaceholder')}
          onChange={(event) => setDraft((current) => ({ ...current, targetId: event.target.value }))}
          style={{ width: '100%' }}
        />
        <Input
          aria-label={t('keywords')}
          value={draft.keyword}
          placeholder={t('keywordPlaceholder')}
          onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))}
          style={{ width: '100%' }}
        />
        <Space style={{ justifyContent: 'flex-end' }}>
          <Button type="primary" onClick={applyFilters}>{t('search')}</Button>
          <Button onClick={resetFilters}>{t('reset')}</Button>
        </Space>
      </div>
      <Table
        scroll={{ x: 900 }}
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={{ spinning: isLoading, tip: t('loading') }}
        pagination={{
          current: page,
          total,
          pageSize: size,
          onChange: (p, ps) => { setPage(p); setSize(ps); },
          showTotal: (count) => t('total', { count }),
        }}
      />
      <Drawer closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('close') }} title={t('detailTitle')} width="min(760px, 95vw)" open={selected !== null} onClose={() => setSelected(null)}>
        {selected && <>
          <Descriptions column={1} bordered size="small" items={[
            { key: 'id', label: t('logId'), children: selected.id },
            { key: 'action', label: t('rawAction'), children: selected.action },
            { key: 'trigger', label: t('trigger'), children: triggerText(selected) },
            { key: 'event', label: t('eventType'), children: String(parseDetail(selected).eventType || '—') },
          ]} />
          <AuditJsonBlock titleKey="fullRecord" value={selected} />
          <AuditJsonBlock titleKey="eventDetail" value={parseDetail(selected)} />
        </>}
      </Drawer>
    </Card>
  );
}
