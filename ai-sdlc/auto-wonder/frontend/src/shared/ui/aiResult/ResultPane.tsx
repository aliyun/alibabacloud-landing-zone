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
      return <Alert type="error" showIcon message="生成失败" description={session.error ?? '请重试'} />;
    }
    if (session?.status === 'CANCELED') {
      return <Empty description="会话已取消" />;
    }
    return <Empty description="发起对话后这里出现结构化结果" />;
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
        message.success('已应用到创建表单');
        onConfirmed?.(payload);
        return;
      }
      await runWithAccess('READ_WRITE', '确认 AI 结果落库', async () => {
        await apiClient.post(`/api/ai/sessions/${session.id}/confirm`, { resultJson: payload });
        message.success('已确认落库');
        onConfirmed?.(payload);
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '确认落库失败，请稍后重试';
      message.error(errorMessage);
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {isDone && (
        <Alert type="success" showIcon message={isAgentConfig ? '已应用到创建表单' : '已生效'} style={{ marginBottom: 12 }} />
      )}
      <div style={{ flex: 1, overflow: 'auto' }}>
        {Renderer ? (
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          <Renderer value={(draft ?? parsed) as any} onChange={setDraft} disabled={disabled} />
        ) : (
          <>
            <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>结果 JSON</Text>
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
          {isAgentConfig ? '确认使用草稿' : '确认落库'}
        </Button>
      )}
    </div>
  );
}
