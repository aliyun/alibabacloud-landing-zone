import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Alert, Typography, Input, message } from 'antd';
import { apiClient } from '@/shared/api/client';
import { rendererRegistry } from './registry';
import type { AiSession } from '@/shared/types/ai';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const { TextArea } = Input;

interface ResultPaneProps {
  session: AiSession | null;
  onConfirmed?: (resultJson: string) => void;
}

export function ResultPane({ session, onConfirmed }: ResultPaneProps) {
  const { t } = useTranslation('shared');
  const runWithAccess = useAccessCommand();
  const parsed = useMemo(() => {
    if (!session?.resultJson) return null;
    try {
      return JSON.parse(session.resultJson) as unknown;
    } catch {
      return null;
    }
  }, [session?.resultJson]);

  const [draft, setDraft] = useState<unknown>(parsed);
  const [rawDraft, setRawDraft] = useState<string>(session?.resultJson ?? '');
  const [confirming, setConfirming] = useState(false);

  // Reset local draft whenever the source resultJson changes (multi-turn refresh).
  useEffect(() => {
    setDraft(parsed);
    setRawDraft(session?.resultJson ?? '');
  }, [parsed, session?.resultJson]);

  if (!session || !session.resultJson || parsed === null) {
    if (session?.status === 'FAILED') {
      return <Alert type="error" showIcon message={t('result.failed')} description={session.error ?? t('result.retry')} />;
    }
    if (session?.status === 'CANCELED') {
      return <Empty description={t('result.cancelled')} />;
    }
    return <Empty description={t('result.empty')} />;
  }

  const Renderer = rendererRegistry[session.scene];
  const disabled = session.status !== 'WAIT_USER';
  const isDone = session.status === 'COMPLETED';
  const isAgentConfig = session.scene === 'AGENT_CONFIG_GEN';

  const confirm = async () => {
    const payload = Renderer ? JSON.stringify(draft) : rawDraft;
    setConfirming(true);
    try {
      if (isAgentConfig) {
        message.success(i18n.t('shared:result.applied'));
        onConfirmed?.(payload);
        return;
      }
      await runWithAccess('READ_WRITE', t('result.confirmAction'), async () => {
        await apiClient.post(`/api/ai/sessions/${session.id}/confirm`, { resultJson: payload });
        message.success(i18n.t('shared:result.saved'));
        onConfirmed?.(payload);
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : i18n.t('shared:result.saveFailed');
      message.error(errorMessage);
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {isDone && (
        <Alert type="success" showIcon message={isAgentConfig ? t('result.applied') : t('result.effective')} style={{ marginBottom: 12 }} />
      )}
      <div style={{ flex: 1, overflow: 'auto' }}>
        {Renderer ? (
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          <Renderer value={(draft ?? parsed) as any} onChange={setDraft} disabled={disabled} />
        ) : (
          <>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('result.json')}</Text>
            <TextArea
              value={rawDraft}
              disabled={disabled}
              autoSize={{ minRows: 6, maxRows: 20 }}
              onChange={(e) => setRawDraft(e.target.value)}
            />
          </>
        )}
      </div>
      {session.status === 'WAIT_USER' && (
        <Button type="primary" block style={{ marginTop: 12 }} loading={confirming} onClick={confirm}>
          {isAgentConfig ? t('result.useDraft') : t('result.save')}
        </Button>
      )}
    </div>
  );
}
