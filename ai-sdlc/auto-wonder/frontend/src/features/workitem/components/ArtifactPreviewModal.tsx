import { useCallback, useEffect, useState } from 'react';
import { Button, Modal } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { FilePreview } from '@/shared/ui/FilePreview';
import type { Artifact } from '@/shared/types/workitem';
import { getArtifactDownloadUrl, getArtifactPreviewBlob } from '../api';

interface ArtifactPreviewModalProps {
  open: boolean;
  artifact: Artifact | null;
  onClose: () => void;
}

export function ArtifactPreviewModal({ open, artifact, onClose }: ArtifactPreviewModalProps) {
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null);
  const id = artifact?.id;
  const loadBlob = useCallback(() => getArtifactPreviewBlob(id!), [id]);
  useEffect(() => {
    let cancelled = false;
    setDownloadUrl(null);
    if (open && id != null) {
      getArtifactDownloadUrl(id).then(url => { if (!cancelled) setDownloadUrl(url); }).catch(() => undefined);
    }
    return () => { cancelled = true; };
  }, [id, open]);

  return <Modal title={artifact?.name ?? '产物预览'} open={open} onCancel={onClose} width={880} forceRender
    footer={downloadUrl ? [<Button key="download" icon={<DownloadOutlined />} href={downloadUrl} target="_blank" rel="noreferrer">下载</Button>] : null}>
    {open && artifact && <FilePreview name={artifact.name} size={artifact.size} loadBlob={loadBlob} />}
  </Modal>;
}
