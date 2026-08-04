import { Alert, Input, Space, Tag, Typography } from 'antd';
import type { AiResultRendererProps } from './types';
import type { AgentConfigGenResult, AgentConfigRecommendations } from '@/shared/types/ai';

const { Text } = Typography;
const { TextArea } = Input;

const recommendationLabels: Record<keyof AgentConfigRecommendations, string> = {
  executors: '执行器',
  skills: '技能',
  memories: '知识库',
  workflows: '工作流',
};

function lines(items?: string[]) {
  return (items ?? []).join('\n');
}

function toList(text: string) {
  return text
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean);
}

function normalizeRoleCode(value: string) {
  return value.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+/g, '');
}

export function AgentConfigGenRenderer({ value, onChange, disabled }: AiResultRendererProps<AgentConfigGenResult>) {
  const update = (patch: Partial<AgentConfigGenResult>) => onChange({ ...value, ...patch });
  const updateRecommendations = (key: keyof AgentConfigRecommendations, text: string) =>
    update({
      recommendations: {
        ...(value.recommendations ?? {}),
        [key]: toList(text),
      },
    });

  const missingFields = value.missingFields ?? [];
  const questions = value.clarifyingQuestions ?? [];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {(missingFields.length > 0 || questions.length > 0) && (
        <Alert
          type="warning"
          showIcon
          message="有待补充信息"
          description={
            <Space direction="vertical" size={4}>
              {missingFields.length > 0 && (
                <div>
                  <Text strong>字段：</Text>
                  <Space wrap size={4}>
                    {missingFields.map((field) => <Tag key={field}>{field}</Tag>)}
                  </Space>
                </div>
              )}
              {questions.map((question) => <Text key={question}>{question}</Text>)}
            </Space>
          }
        />
      )}

      <div>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>名称</Text>
        <Input value={value.name ?? ''} disabled={disabled} onChange={(e) => update({ name: e.target.value })} />
      </div>

      <div>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>头像 URL</Text>
        <Input value={value.avatarUrl ?? ''} disabled={disabled} onChange={(e) => update({ avatarUrl: e.target.value })} />
      </div>

      <Space wrap style={{ width: '100%' }} align="start">
        <div style={{ minWidth: 220, flex: 1 }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>角色名称</Text>
          <Input value={value.roleName ?? ''} disabled={disabled} onChange={(e) => update({ roleName: e.target.value })} />
        </div>
        <div style={{ minWidth: 180, flex: 1 }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>角色码</Text>
          <Input
            value={value.roleCode ?? ''}
            disabled={disabled}
            onChange={(e) => update({ roleCode: normalizeRoleCode(e.target.value) })}
          />
        </div>
      </Space>

      <div>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>业务背景</Text>
        <TextArea
          value={value.businessBackground ?? ''}
          disabled={disabled}
          autoSize={{ minRows: 3, maxRows: 6 }}
          onChange={(e) => update({ businessBackground: e.target.value })}
        />
      </div>

      <div>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>工作职责</Text>
        <TextArea
          value={value.responsibilities ?? ''}
          disabled={disabled}
          autoSize={{ minRows: 3, maxRows: 6 }}
          onChange={(e) => update({ responsibilities: e.target.value })}
        />
      </div>

      <div>
        <Text strong style={{ display: 'block', marginBottom: 8 }}>推荐配置</Text>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          {(Object.keys(recommendationLabels) as (keyof AgentConfigRecommendations)[]).map((key) => (
            <div key={key}>
              <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{recommendationLabels[key]}（每行一项）</Text>
              <TextArea
                value={lines(value.recommendations?.[key])}
                disabled={disabled}
                autoSize={{ minRows: 1, maxRows: 4 }}
                onChange={(e) => updateRecommendations(key, e.target.value)}
              />
            </div>
          ))}
        </Space>
      </div>
    </Space>
  );
}
