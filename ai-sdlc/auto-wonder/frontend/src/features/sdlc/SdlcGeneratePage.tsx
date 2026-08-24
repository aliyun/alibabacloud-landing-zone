import { Card, Button, Space, Alert } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
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
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/sdlcs')}>返回</Button>
      </Space>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="AI 生成的流程会落库为草稿（DRAFT），需到 SDLC 列表显式启用后才会生效。"
      />
      <Card title="AI 生成 SDLC" styles={{ body: { height: '70vh', padding: 0 } }}>
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
