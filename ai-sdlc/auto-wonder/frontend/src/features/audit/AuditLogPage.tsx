import { useMemo, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Input, Segmented, Select, Space, Table, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { countAuditLogs, listAuditLogs } from './api';
import type { AuditLog, AuditLogFilters } from './api';
import type { ColumnsType } from 'antd/es/table';

interface AuditDetail {
  actorType?: string;
  triggerType?: string;
  triggerSource?: string;
  eventType?: string;
  [key: string]: unknown;
}

const moduleOptions = [
  { label: '全部模块', value: '' },
  { label: '工单', value: 'WORKITEM' },
  { label: '数字员工', value: 'AGENT' },
  { label: '技能', value: 'SKILL' },
  { label: '执行', value: 'DISPATCH' },
  { label: '状态模板', value: 'STATUS_TEMPLATE' },
];

const actionOptions = [
  { label: '全部操作', value: '' },
  { label: '创建', value: 'CREATE' },
  { label: '更新', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '审核通过', value: 'APPROVE' },
  { label: '驳回', value: 'REJECT' },
  { label: '运行事件', value: 'RUNTIME_EVENT' },
];

const targetTypeOptions = [
  { label: '全部目标类型', value: '' },
  { label: '工单', value: 'workitem' },
  { label: '数字员工', value: 'agent' },
  { label: '技能', value: 'skill' },
  { label: '执行', value: 'dispatch' },
];

const timeRangeOptions = [
  { label: '全部时间', value: '' },
  { label: '近 24 小时', value: '24h' },
  { label: '近 7 天', value: '7d' },
  { label: '近 30 天', value: '30d' },
];

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

function parseDetail(record: AuditLog): AuditDetail {
  const raw = record.detailJson || record.detail;
  if (!raw) {
    return {};
  }
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function detailText(record: AuditLog): string {
  const detail = parseDetail(record);
  if (Object.keys(detail).length === 0) {
    return record.detail || record.detailJson || '-';
  }
  const fields = ['message', 'error', 'stepName', 'path', 'status'];
  const summary = fields
    .filter((field) => detail[field] !== undefined && detail[field] !== null && detail[field] !== '')
    .map((field) => `${field}: ${String(detail[field])}`)
    .join('；');
  return summary || String(detail.eventType || '-');
}

function triggerText(record: AuditLog): string {
  const detail = parseDetail(record);
  const parts = [detail.triggerType, detail.triggerSource].filter(Boolean).map(String);
  return parts.length > 0 ? parts.join(' / ') : '-';
}

export function AuditLogPage() {
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
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

  const columns: ColumnsType<AuditLog> = [
    { title: '时间', dataIndex: 'gmtCreate', width: 160, render: (t: string) => new Date(t).toLocaleString('zh-CN') },
    { title: '操作人', dataIndex: 'actorName', width: 140, render: (_, record) => {
      const actorType = record.actorType || parseDetail(record).actorType;
      return `${record.actorName || `#${record.actorId}`}${actorType ? ` (${actorType})` : ''}`;
    } },
    { title: '操作', dataIndex: 'action', width: 200, render: (action: string, record) => (
      <><div>{actionOptions.find((option) => option.value === action)?.label || action}</div>
        <Typography.Text type="secondary">{moduleOptions.find((option) => option.value === record.module)?.label || record.module}</Typography.Text></>
    ) },
    { title: '对象', width: 150, render: (_, record) => `${targetTypeOptions.find((option) => option.value === record.targetType)?.label || record.targetType || '—'}${record.targetId != null ? ` #${record.targetId}` : ''}` },
    { title: '摘要', ellipsis: true, render: (_, record) => detailText(record) },
    { title: '', width: 90, render: (_, record) => <Button type="link" onClick={() => setSelected(record)}>详情</Button> },
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
    <Card title="审计日志">
      <Space direction="vertical" size={8} style={{ marginBottom: 20, width: '100%' }}>
        <Segmented value={filters.actorType || ''} options={[
          { label: '人工操作', value: 'HUMAN' }, { label: '数字员工', value: 'AGENT' },
          { label: '系统', value: 'SYSTEM' }, { label: '全部', value: '' },
        ]} onChange={(value) => { setFilters((current) => ({ ...current, actorType: value || undefined })); setPage(1); }} />
        <Typography.Text type="secondary">默认查看人工操作；数字员工的运行事件可切换查看，完整字段保留在详情中。</Typography.Text>
      </Space>
      {isError && <Alert type="error" showIcon message="审计日志加载失败，请稍后重试" /> }
      <Space wrap size={12} style={{ marginBottom: 16 }}>
        <Select
          value={draft.module}
          options={moduleOptions}
          onChange={(value) => setDraft((current) => ({ ...current, module: value }))}
          style={{ width: 150 }}
        />
        <Select
          value={draft.action}
          options={actionOptions}
          onChange={(value) => setDraft((current) => ({ ...current, action: value }))}
          style={{ width: 150 }}
        />
        <Select
          value={draft.targetType}
          options={targetTypeOptions}
          onChange={(value) => setDraft((current) => ({ ...current, targetType: value }))}
          style={{ width: 160 }}
        />
        <Input
          value={draft.actorId}
          placeholder="按操作人 ID 筛选"
          onChange={(event) => setDraft((current) => ({ ...current, actorId: event.target.value }))}
          style={{ width: 150 }}
        />
        <Input
          value={draft.targetId}
          placeholder="按目标 ID 筛选"
          onChange={(event) => setDraft((current) => ({ ...current, targetId: event.target.value }))}
          style={{ width: 150 }}
        />
        <Select
          value={draft.timeRange}
          options={timeRangeOptions}
          onChange={(value) => setDraft((current) => ({ ...current, timeRange: value }))}
          style={{ width: 150 }}
        />
        <Input
          value={draft.keyword}
          placeholder="搜索详情关键词"
          onChange={(event) => setDraft((current) => ({ ...current, keyword: event.target.value }))}
          style={{ width: 220 }}
        />
        <Button type="primary" onClick={applyFilters}>搜索</Button>
        <Button onClick={resetFilters}>重置</Button>
      </Space>
      <Table
        scroll={{ x: 900 }}
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={isLoading}
        pagination={{
          current: page,
          total,
          pageSize: size,
          onChange: (p, ps) => { setPage(p); setSize(ps); },
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
      <Drawer title="审计日志详情" width="min(760px, 95vw)" open={selected !== null} onClose={() => setSelected(null)}>
        {selected && <>
          <Descriptions column={1} bordered size="small" items={[
            { key: 'id', label: '日志 ID', children: selected.id },
            { key: 'action', label: '原始操作', children: selected.action },
            { key: 'trigger', label: '触发机制', children: triggerText(selected) },
            { key: 'event', label: '事件类型', children: String(parseDetail(selected).eventType || '—') },
          ]} />
          <Typography.Title level={5}>完整记录</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', background: '#fafafa', padding: 16 }}>{JSON.stringify(selected, null, 2)}</pre>
          <Typography.Title level={5}>事件详情</Typography.Title>
          <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(parseDetail(selected), null, 2)}</pre>
        </>}
      </Drawer>
    </Card>
  );
}
