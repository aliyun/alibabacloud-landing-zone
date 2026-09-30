import { Alert, Button, Collapse, List, Popconfirm, Space, Tag, Typography } from 'antd';
import type { MemoryImportSource } from './api';
import { useMemoryImportReceipts, useMemoryImportSources, useMemoryImportStatus } from './hooks';

function ImportSource({ source }: { source: MemoryImportSource }) {
  const { data: receipts = [] } = useMemoryImportReceipts(source.id, source.status !== 'MISSING');
  const status = useMemoryImportStatus();
  const change = (next: string) => status.mutate({ sourceId: source.id, status: next, version: source.version });
  return <List.Item actions={[
    source.status === 'PAUSED' ? <Button key="resume" type="link" onClick={() => change('ACTIVE')}>恢复</Button> : <Button key="pause" type="link" onClick={() => change('PAUSED')}>暂停</Button>,
    <Button key="recurate" type="link" onClick={() => change('RECURATE_REQUESTED')}>重新整理</Button>,
    <Popconfirm key="retire" title="退役后将停止继续导入此来源，确认？" onConfirm={() => change('RETIRED')}><Button type="link" danger>退役</Button></Popconfirm>,
  ]}>
    <List.Item.Meta title={<Space><Typography.Text>{source.providerFamily} · {source.logicalPath}</Typography.Text><Tag>{({ MISSING: '未找到本地文件', ACTIVE: '可导入', PAUSED: '已暂停', RETIRED: '已停止导入', RECURATE_REQUESTED: '等待重新整理', STALE: '长期未检查', TOO_LARGE: '文件过大' } as Record<string, string>)[source.status] ?? source.status}</Tag></Space>} description={`数字人 #${source.agentId} · 最近记录中成功导入 ${receipts.filter(item => item.outcome === 'SUCCEEDED').length} 次 · 最近检查 ${source.lastSeenAt ? new Date(source.lastSeenAt).toLocaleString() : '—'}`} />
  </List.Item>;
}

export function MemoryImportSourcesPanel({ administrator, agentId }: { administrator: boolean; agentId?: number }) {
  const { data = [] } = useMemoryImportSources(administrator);
  if (!administrator) return <Alert type="info" showIcon message="仅工作空间管理员可管理本地记忆来源" />;
  const selected = agentId === undefined ? data : data.filter(source => source.agentId === agentId);
  const missing = selected.filter(source => source.status === 'MISSING');
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="本地来源是 Qoder/QoderCN 的旧记忆文件，与平台历史记忆迁移不同。" description="未找到本地文件不代表平台记忆丢失；可能尚未创建、已移动或不是可读取的普通文件。已经导入的平台记忆不会随本地文件消失而删除。" />
    <List dataSource={selected.filter(source => source.status !== 'MISSING')} locale={{ emptyText: '暂无可导入的本地文件' }} renderItem={source => <ImportSource source={source} />} />
    {missing.length > 0 && <Collapse items={[{ key: 'missing', label: `未找到本地文件（${missing.length}）`, children: <List dataSource={missing} renderItem={source => <ImportSource source={source} />} /> }]} />}
  </Space>;
}
