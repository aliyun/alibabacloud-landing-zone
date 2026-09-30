import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Card, Alert } from 'antd';
import { useNavigate } from 'react-router-dom';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { KeyboardEvent, MouseEvent } from 'react';

export function SdlcGeneratePage() {
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const guardAiCommand = (event: MouseEvent<HTMLDivElement> | KeyboardEvent<HTMLDivElement>) => {
    const allowed = accessCommand('READ_WRITE', 'AI 生成 SDLC', () => true);
    if (!allowed) {
      event.preventDefault();
      event.stopPropagation();
    }
  };

  return (
    <div style={{ padding: 24 }}>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="AI 生成的流程会落库为草稿（DRAFT），需到 SDLC 列表显式启用后才会生效。"
      />
      <Card className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/sdlcs" label="返回" /><span>AI 生成 SDLC</span></span>} />} styles={{ body: { height: '70vh', padding: 0 } }}>
        <div
          style={{ height: '100%' }}
          onClickCapture={(event) => {
            if ((event.target as HTMLElement).closest('button')) {
              guardAiCommand(event);
            }
          }}
          onKeyDownCapture={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              guardAiCommand(event);
            }
          }}
        >
          <AiSessionPanel
            scene="SDLC_GEN"
            bizRefType='ORG'
            bizRefId={0}
            onConfirm={() => accessCommand('READ_WRITE', 'AI 生成 SDLC', () => navigate('/sdlcs'))}
          />
        </div>
      </Card>
    </div>
  );
}
