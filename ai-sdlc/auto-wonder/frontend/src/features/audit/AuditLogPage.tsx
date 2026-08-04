import { useMemo, useState } from 'react';
import { Button, Card, Input, Select, Space, Table } from 'antd';
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
  { label: '状态模板', value: 'STATUS_TEMPLATE' },
];

const actionOptions = [
  { label: '全部操作', value: '' },
  { label: '创建', value: 'CREATE' },
  { label: '更新', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '审核通过', value: 'APPROVE' },
  { label: '驳回', value: 'REJECT' },
];

const targetTypeOptions = [
  { label: '全部目标类型', value: '' },
  { label: '工单', value: 'workitem' },
  { label: '数字员工', value: 'agent' },
  { label: '技能', value: 'skill' },
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
  const fields = ['path', 'method', 'status', 'workitemId', 'dispatchId', 'stepName', 'message', 'error'];
  const summary = fields
    .filter((field) => detail[field] !== undefined && detail[field] !== null && detail[field] !== '')
    .map((field) => `${field}: ${String(detail[field])}`)
    .join('；');
  return summary || record.detailJson || '-';
}

function triggerText(record: AuditLog): string {
  const detail = parseDetail(record);
  const parts = [detail.triggerType, detail.triggerSource].filter(Boolean).map(String);
  return parts.length > 0 ? parts.join(' / ') : '-';
}

export function AuditLogPage() {
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
  const [filters, setFilters] = useState<Omit<AuditLogFilters, 'page' | 'size'>>({});

  const queryFilters = useMemo(
    () => ({ page, size, ...filters }),
    [page, size, filters],
  );

  const { data = [], isLoading } = useQuery({
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
    { title: '模块', dataIndex: 'module', width: 100 },
    { title: '操作', dataIndex: 'action', width: 120 },
    { title: '触发机制', dataIndex: 'detailJson', width: 170, render: (_, record) => triggerText(record) },
    { title: '事件类型', dataIndex: 'detailJson', width: 150, render: (_, record) => parseDetail(record).eventType || '-' },
    { title: '目标类型', dataIndex: 'targetType', width: 100, render: (v: string | null) => v || '-' },
    { title: '目标ID', dataIndex: 'targetId', width: 100, render: (v: string | null) => v || '-' },
    { title: '详情', dataIndex: 'detail', ellipsis: true, render: (_, record) => detailText(record) },
  ];

  const applyFilters = () => {
    setFilters({
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
    setFilters({});
    setPage(1);
  };

  return (
    <Card title="审计日志">
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
    </Card>
  );
}
