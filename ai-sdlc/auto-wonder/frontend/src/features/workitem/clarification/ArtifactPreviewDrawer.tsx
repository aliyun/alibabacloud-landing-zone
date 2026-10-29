import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Space, Tooltip } from 'antd';
import {
  CloseOutlined, DownloadOutlined, FullscreenExitOutlined, FullscreenOutlined, GlobalOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { FilePreview } from '@/shared/ui/FilePreview';
import { ResizeHandle } from '@/shared/ui/ResizeHandle';
import type { Artifact } from '@/shared/types/workitem';
import { getArtifactDownloadUrl, getArtifactPreviewBlob } from '../api';

export function ArtifactPreviewDrawer({ artifact, onClose }: {
  artifact: Artifact;
  onClose: () => void;
}) {
  const { t } = useTranslation('workitem');
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null);
  const [maximized, setMaximized] = useState(false);
  const [width, setWidth] = useState<number | null>(null);
  const panelRef = useRef<HTMLElement>(null);
  const loadBlob = useCallback(() => getArtifactPreviewBlob(artifact.id), [artifact.id]);

  useEffect(() => {
    let cancelled = false;
    setDownloadUrl(null);
    getArtifactDownloadUrl(artifact.id)
      .then(url => { if (!cancelled) setDownloadUrl(url); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [artifact.id]);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  const maximizeLabel = t(maximized ? 'artifacts.restorePreview' : 'artifacts.maximizePreview');
  const currentWidth = width ?? Math.min(560, window.innerWidth * 0.44);
  return (
    <aside
      ref={panelRef}
      role="dialog"
      aria-modal="false"
      aria-label={artifact.name}
      data-testid="clarification-artifact-browser"
      className={`aw-clarify-artifact-browser${maximized ? ' is-maximized' : ''}`}
      style={{ width: maximized ? '100vw' : width ?? 'min(560px, 44vw)' }}
    >
      {!maximized && (
        <ResizeHandle
          direction="horizontal"
          value={currentWidth}
          measureValue={() => panelRef.current?.getBoundingClientRect().width ?? currentWidth}
          min={320}
          max={window.innerWidth}
          onChange={setWidth}
          aria-label={t('artifacts.resizePreview')}
        />
      )}
      <header className="aw-clarify-artifact-header">
        <div className="aw-clarify-artifact-address">
          <GlobalOutlined aria-hidden="true" />
          <span>{artifact.name}</span>
        </div>
        <Space size={2} className="aw-clarify-artifact-actions">
          <Tooltip title={maximizeLabel}>
            <Button
              type="text"
              size="small"
              aria-label={maximizeLabel}
              icon={maximized ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
              onClick={() => setMaximized(value => !value)}
            />
          </Tooltip>
          {downloadUrl && (
            <Tooltip title={t('artifacts.download')}>
              <Button
                type="text"
                size="small"
                aria-label={t('artifacts.download')}
                icon={<DownloadOutlined />}
                href={downloadUrl}
                target="_blank"
                rel="noreferrer"
              />
            </Tooltip>
          )}
          <Tooltip title={t('artifacts.closePreview')}>
            <Button
              type="text"
              size="small"
              aria-label={t('artifacts.closePreview')}
              icon={<CloseOutlined />}
              onClick={onClose}
            />
          </Tooltip>
        </Space>
      </header>
      <div className="aw-clarify-artifact-body">
        <FilePreview name={artifact.name} size={artifact.size} loadBlob={loadBlob} />
      </div>
    </aside>
  );
}
