import { Button, Card, List } from 'antd';
import { useState } from 'react';
import type { Artifact } from '@/shared/types/workitem';
import { ArtifactPreviewModal } from '@/features/workitem/components/ArtifactPreviewModal';
export function RunArtifacts({ artifacts }: { artifacts: Artifact[] }) { const [preview, setPreview] = useState<Artifact | null>(null); return <Card title="运行产物" size="small"><List locale={{ emptyText: '暂无产物' }} dataSource={artifacts} renderItem={(artifact) => <List.Item actions={[<Button key="preview" type="link" onClick={() => setPreview(artifact)}>预览</Button>]}>{artifact.name}</List.Item>} /><ArtifactPreviewModal open={preview != null} artifact={preview} onClose={() => setPreview(null)} /></Card>; }
