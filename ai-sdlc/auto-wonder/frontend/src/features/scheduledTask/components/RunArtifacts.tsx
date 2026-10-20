import { useTranslation } from 'react-i18next';
import { Button, Card, List, Popconfirm } from 'antd';
import { useState } from 'react';
import type { Artifact } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from '@/features/workitem/components/ArtifactPreviewModal';
export function RunArtifacts({ artifacts, onDelete }: { artifacts: Artifact[]; onDelete?: (artifact: Artifact) => void }) {
  const { t } = useTranslation('scheduledTask'); const [preview, setPreview] = useState<Artifact | null>(null); return <Card title={t('artifacts.title')} size="small"><List locale={{ emptyText: t('artifacts.empty') }} dataSource={artifacts} renderItem={(artifact) => <List.Item actions={[<Button key="preview" type="link" onClick={() => setPreview(artifact)}>{t('actions.preview')}</Button>, ...(onDelete ? [<Popconfirm key="delete" title={t('confirm.deleteArtifact')} okText={t('actions.confirmDelete')} okButtonProps={{ danger: true }} cancelText={t('actions.cancel')} onConfirm={() => onDelete(artifact)}><Button type="link" danger>{t('actions.delete')}</Button></Popconfirm>] : [])]}>{artifact.name}</List.Item>} /><ArtifactPreviewModal open={preview != null} artifact={preview} onClose={() => setPreview(null)} /></Card>; }
