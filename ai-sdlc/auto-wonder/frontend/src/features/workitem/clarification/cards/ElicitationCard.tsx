import { Tag, Typography } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import type { AcpElicitationAction } from '../types';
import { CLARIFICATION_THEME } from '../theme';
import { parseElicitationSchema, type ElicitationField } from './schemaForm';

export interface ElicitationReply {
  action: 'accept' | 'decline';
  content?: Record<string, unknown>;
}

interface ElicitationCardProps {
  requestId: string;
  message?: string;
  schema: unknown;
  resolved?: boolean;
  action?: AcpElicitationAction;
  answers?: Record<string, unknown>;
}

const RESOLVED_LABEL: Record<AcpElicitationAction, string> = {
  accept: '已回答',
  decline: '已跳过',
  cancel: '已取消',
};

/**
 * 已回答问题的内联历史展示。进行中的问题由 ElicitationWizard 停靠卡片承担，
 * 这里把题干与最终回传值一并回显，保证执行详情里的历史问答可读。
 */
export function ElicitationCard({
  requestId, message, schema, resolved = false, action, answers,
}: ElicitationCardProps) {
  const form = parseElicitationSchema(schema);

  return (
    <div
      data-testid={`elicitation-card-${requestId}`}
      style={{
        marginBottom: 8, padding: '10px 12px',
        borderRadius: CLARIFICATION_THEME.radiusBlock,
        border: `1px solid ${CLARIFICATION_THEME.controlBorder}`,
        backgroundColor: CLARIFICATION_THEME.surface,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: resolved ? 8 : 0 }}>
        <QuestionCircleOutlined style={{ color: 'var(--aw-accent-text)' }} />
        <Typography.Text strong style={{ fontSize: 16, flex: 1 }}>
          {message || form.title || '需要你的确认'}
        </Typography.Text>
        {resolved ? (
          <Tag color={action === 'accept' ? 'green' : 'default'} style={{ marginInlineEnd: 0 }}>
            {(action && RESOLVED_LABEL[action]) || '已处理'}
          </Tag>
        ) : null}
      </div>

      {resolved ? <ResolvedAnswers fields={form.fields} answers={answers} /> : null}
    </div>
  );
}

function ResolvedAnswers({
  fields, answers,
}: {
  fields: ElicitationField[];
  answers?: Record<string, unknown>;
}) {
  const entries = Object.entries(answers ?? {});
  if (entries.length === 0) return null;

  return (
    <div>
      {entries.map(([key, value]) => {
        const field = fields.find((f) => f.name === key);
        // 问题正文在 description（qodercli 把题目塞在首行），label 只是短话题。
        // 实时向导两者都渲染；已答卡片若只回显 label，执行详情里就只剩答案、
        // 读不出当初到底问了什么，所以这里把题干一并回显。
        const question = field?.description;
        return (
          <div key={key} style={{ marginBottom: 8 }}>
            <Typography.Text strong style={{ fontSize: 14 }}>
              {field?.label ?? key}
            </Typography.Text>
            {question ? (
              <div style={{
                fontSize: 14, color: CLARIFICATION_THEME.textSecondary,
                whiteSpace: 'pre-wrap', marginTop: 2,
              }}>
                {question}
              </div>
            ) : null}
            <div style={{ marginTop: 4, fontSize: 14, whiteSpace: 'pre-wrap' }}>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>答：</Typography.Text>
              <span data-testid={`elicitation-answer-${key}`}>
                {typeof value === 'string' ? value : JSON.stringify(value)}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
