import { useMemo } from 'react';
import { Typography, Tag, Spin } from 'antd';
import { LoadingOutlined, ToolOutlined, CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { StreamedEvent } from './hooks';

interface ConversationEventViewProps {
  events: StreamedEvent[];
  isProcessing: boolean;
}

interface AccumulatedText {
  text: string;
  thinking: string;
  toolUses: Array<{ tool: string; callId: string; status: string; input?: string; output?: string }>;
  errors: string[];
  logs: string[];
  status: string | null;
}

export function ConversationEventView({ events, isProcessing }: ConversationEventViewProps) {
  const accumulated = useMemo(() => {
    const acc: AccumulatedText = { text: '', thinking: '', toolUses: [], errors: [], logs: [], status: null };
    for (const ev of events) {
      applyEvent(acc, ev);
    }
    return acc;
  }, [events]);

  if (events.length === 0 && isProcessing) {
    return (
      <div style={{ padding: 16, textAlign: 'center' }}>
        <Spin indicator={<LoadingOutlined />} />
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          等待 AI 响应...
        </Typography.Text>
      </div>
    );
  }

  if (events.length === 0) {
    return null;
  }

  return (
    <div style={{ padding: '8px 0' }}>
      {accumulated.thinking ? (
        <details style={{ marginBottom: 8 }}>
          <summary style={{ cursor: 'pointer', color: '#8c8c8c', fontSize: 12 }}>
            思考过程
          </summary>
          <div style={{
            fontSize: 12, color: '#8c8c8c', whiteSpace: 'pre-wrap',
            maxHeight: 200, overflow: 'auto', padding: '4px 8px',
            backgroundColor: '#fafafa', borderRadius: 4, marginTop: 4,
          }}>
            <MarkdownView content={accumulated.thinking} />
          </div>
        </details>
      ) : null}

      {accumulated.toolUses.length > 0 ? (
        <div style={{ marginBottom: 8 }}>
          {accumulated.toolUses.map((tu, i) => (
            <details key={i} style={{ marginBottom: 4 }}>
              <summary style={{ cursor: 'pointer' }}>
                <Tag
                  icon={<ToolOutlined />}
                  color={tu.status === 'completed' ? 'green' : tu.status === 'failed' ? 'red' : 'processing'}
                >
                  {tu.tool}
                  {tu.status === 'completed' && <CheckCircleOutlined style={{ marginLeft: 4 }} />}
                  {tu.status === 'failed' && <CloseCircleOutlined style={{ marginLeft: 4 }} />}
                </Tag>
              </summary>
              {tu.input ? (
                <div style={{ marginTop: 4 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>输入</Typography.Text>
                  <pre style={{
                    fontSize: 11, whiteSpace: 'pre-wrap', maxHeight: 150, overflow: 'auto',
                    padding: '4px 8px', backgroundColor: '#f6f8fa', borderRadius: 4, marginTop: 2,
                  }}>
                    {tu.input}
                  </pre>
                </div>
              ) : null}
              {tu.output ? (
                <div style={{ marginTop: 4 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>输出</Typography.Text>
                  <pre style={{
                    fontSize: 11, whiteSpace: 'pre-wrap', maxHeight: 150, overflow: 'auto',
                    padding: '4px 8px', backgroundColor: '#f6f8fa', borderRadius: 4, marginTop: 2,
                  }}>
                    {tu.output}
                  </pre>
                </div>
              ) : null}
            </details>
          ))}
        </div>
      ) : null}

      {accumulated.text ? (
        <MarkdownView content={accumulated.text} />
      ) : null}

      {accumulated.logs.length > 0 ? (
        <details style={{ marginTop: 8 }}>
          <summary style={{ cursor: 'pointer', color: '#8c8c8c', fontSize: 12 }}>
            日志 ({accumulated.logs.length})
          </summary>
          <pre style={{
            fontSize: 11, color: '#595959', whiteSpace: 'pre-wrap',
            maxHeight: 200, overflow: 'auto', padding: '4px 8px',
            backgroundColor: '#fafafa', borderRadius: 4, marginTop: 4,
          }}>
            {accumulated.logs.join('\n')}
          </pre>
        </details>
      ) : null}

      {accumulated.errors.length > 0 ? (
        <div style={{ marginTop: 8 }}>
          {accumulated.errors.map((err, i) => (
            <Typography.Text key={i} type="danger" style={{ display: 'block', fontSize: 12 }}>
              {err}
            </Typography.Text>
          ))}
        </div>
      ) : null}

      {isProcessing && !accumulated.text && (
        <Spin indicator={<LoadingOutlined />} style={{ marginTop: 8 }} />
      )}
    </div>
  );
}

function applyEvent(acc: AccumulatedText, ev: StreamedEvent): void {
  const p = ev.payload;
  if (!p) return;

  switch (ev.eventType) {
    case 'text':
      acc.text += p.content ?? '';
      break;
    case 'thinking':
      acc.thinking += p.content ?? '';
      break;
    case 'tool_use':
      acc.toolUses.push({
        tool: p.tool ?? 'unknown',
        callId: p.callId ?? '',
        status: 'running',
        input: p.input ? (typeof p.input === 'string' ? p.input : JSON.stringify(p.input, null, 2)) : undefined,
      });
      break;
    case 'tool_result': {
      const match = acc.toolUses.find((tu) => tu.callId === p.callId);
      if (match) {
        match.status = p.status ?? 'completed';
        match.output = p.output ? (typeof p.output === 'string' ? p.output : JSON.stringify(p.output, null, 2)) : undefined;
      }
      break;
    }
    case 'status':
      acc.status = p.status ?? null;
      break;
    case 'error':
      acc.errors.push(p.content ?? p.output ?? 'Unknown error');
      break;
    case 'log':
      acc.logs.push(p.content ?? p.output ?? '');
      break;
  }
}
