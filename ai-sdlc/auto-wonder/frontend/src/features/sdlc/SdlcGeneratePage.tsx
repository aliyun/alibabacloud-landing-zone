import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Card, Alert } from 'antd';
import { useNavigate } from 'react-router-dom';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { KeyboardEvent, MouseEvent } from 'react';

export function SdlcGeneratePage() {
  const { t } = useTranslation('sdlc');
  const navigate = useNavigate();
  const accessCommand = useAccessCommand();
  const guardAiCommand = (event: MouseEvent<HTMLDivElement> | KeyboardEvent<HTMLDivElement>) => {
    const allowed = accessCommand('READ_WRITE', t('commands.aiGenerate'), () => true);
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
        message={t('ai.generateWarning')}
      />
      <Card className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/sdlcs" label={t('actions.back')} /><span>{t('commands.aiGenerate')}</span></span>} />} styles={{ body: { height: '70vh', padding: 0 } }}>
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
            onConfirm={() => accessCommand('READ_WRITE', t('commands.aiGenerate'), () => navigate('/sdlcs'))}
          />
        </div>
      </Card>
    </div>
  );
}
