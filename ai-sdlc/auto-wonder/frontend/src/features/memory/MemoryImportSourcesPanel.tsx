import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Collapse, List, Popconfirm, Space, Tag, Typography } from 'antd';
import type { MemoryImportSource } from './api';
import { useMemoryImportReceipts, useMemoryImportSources, useMemoryImportStatus } from './hooks';

function ImportSource({ source }: { source: MemoryImportSource }) {
  const { t } = useTranslation('memory');
  const statusLabels = { MISSING: t('import.status.MISSING'), ACTIVE: t('import.status.ACTIVE'), PAUSED: t('import.status.PAUSED'), RETIRED: t('import.status.RETIRED'), RECURATE_REQUESTED: t('import.status.RECURATE_REQUESTED'), STALE: t('import.status.STALE'), TOO_LARGE: t('import.status.TOO_LARGE') };
  const { data: receipts = [] } = useMemoryImportReceipts(source.id, source.status !== 'MISSING');
  const status = useMemoryImportStatus();
  const change = (next: string) => status.mutate({ sourceId: source.id, status: next, version: source.version });
  return <List.Item actions={[
    source.status === 'PAUSED' ? <Button key="resume" type="link" onClick={() => change('ACTIVE')}>{t('import.resume')}</Button> : <Button key="pause" type="link" onClick={() => change('PAUSED')}>{t('import.pause')}</Button>,
    <Button key="recurate" type="link" onClick={() => change('RECURATE_REQUESTED')}>{t('import.recurate')}</Button>,
    <Popconfirm okText={t('import.retire')} cancelText={t('actions.cancel')} key="retire" title={t('import.retireConfirm')} onConfirm={() => change('RETIRED')}><Button type="link" danger>{t('import.retire')}</Button></Popconfirm>,
  ]}>
    <List.Item.Meta title={<Space><Typography.Text>{source.providerFamily} · {source.logicalPath}</Typography.Text><Tag>{Object.prototype.hasOwnProperty.call(statusLabels, source.status) ? statusLabels[source.status as keyof typeof statusLabels] : source.status}</Tag></Space>} description={t('import.summary', { agentId: source.agentId, imports: t('import.successCount', { count: receipts.filter(item => item.outcome === 'SUCCEEDED').length }), date: source.lastSeenAt ? formatDateTime(source.lastSeenAt) : '—' })} />
  </List.Item>;
}

export function MemoryImportSourcesPanel({ administrator, agentId }: { administrator: boolean; agentId?: number }) {
  const { t } = useTranslation('memory');
  const { data = [] } = useMemoryImportSources(administrator);
  if (!administrator) return <Alert type="info" showIcon message={t('import.adminOnly')} />;
  const selected = agentId === undefined ? data : data.filter(source => source.agentId === agentId);
  const missing = selected.filter(source => source.status === 'MISSING');
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message={t('import.hint')} description={t('import.help')} />
    <List dataSource={selected.filter(source => source.status !== 'MISSING')} locale={{ emptyText: t('import.empty') }} renderItem={source => <ImportSource source={source} />} />
    {missing.length > 0 && <Collapse items={[{ key: 'missing', label: t('import.missingCount', { count: missing.length }), children: <List dataSource={missing} renderItem={source => <ImportSource source={source} />} /> }]} />}
  </Space>;
}
