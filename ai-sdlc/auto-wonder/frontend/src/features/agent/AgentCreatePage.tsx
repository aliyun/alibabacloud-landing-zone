import { type FocusEvent, useRef, type KeyboardEvent, useState } from 'react';
import { Alert, AutoComplete, Button, Card, Form, Input, Space, Typography, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import type { AgentConfigGenResult } from '@/shared/types/ai';
import { useCreateAgent } from './hooks';
import type { CreateAgentRequest } from './api';
import { AGENT_ROLE_CODE_OPTIONS, AGENT_ROLE_NAME_OPTIONS, getRoleCodeByName, getRoleNameByCode } from './constants';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;
const { Text } = Typography;

function normalizeGeneratedRoleCode(value: string) {
  return value.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

export function AgentCreatePage() {
  const navigate = useNavigate();
  const [form] = Form.useForm<CreateAgentRequest>();
  const [mode, setMode] = useState<'natural' | 'advanced'>('advanced');
  const [naturalSessionKey] = useState(0);
  const businessBackgroundRef = useRef<TextAreaRef>(null);
  const createAgent = useCreateAgent();
  const accessCommand = useAccessCommand();

  const syncRoleNameToCode = (value: string) => {
    const roleCode = getRoleCodeByName(value);
    if (roleCode) {
      form.setFieldsValue({ roleCode });
    }
  };

  const syncRoleCodeToName = (value: string) => {
    const roleName = getRoleNameByCode(value);
    if (roleName) {
      form.setFieldsValue({ roleName });
    }
  };

  const handleSubmit = (values: CreateAgentRequest) => {
    accessCommand('READ_WRITE', '创建数字员工', async () => {
      try {
        const agent = await createAgent.mutateAsync(values);
        message.success('数字员工已创建');
        navigate(`/agents/${agent.id}`);
      } catch {
        // ApiError is surfaced by the global interceptor.
      }
    });
  };

  const handleRoleNameSelect = (value: string) => {
    syncRoleNameToCode(value);
  };

  const handleRoleNameChange = (value: string) => {
    syncRoleNameToCode(value);
  };

  const handleRoleCodeSelect = (value: string) => {
    syncRoleCodeToName(value);
  };

  const handleRoleCodeChange = (value: string) => {
    syncRoleCodeToName(value);
  };

  const handleRoleCodeBlur = (e: FocusEvent<HTMLInputElement>) => {
    const normalized = e.target.value.trim().toUpperCase();
    form.setFieldsValue({ roleCode: normalized });
    syncRoleCodeToName(normalized);
  };

  const handleRoleCodeKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === ' ') {
      e.preventDefault();
      const normalized = e.currentTarget.value.trim().toUpperCase();
      form.setFieldsValue({ roleCode: normalized });
      syncRoleCodeToName(normalized);
      businessBackgroundRef.current?.focus();
    }
  };

  const handleGeneratedDraft = (resultJson: string) => {
    try {
      const draft = JSON.parse(resultJson) as Partial<AgentConfigGenResult>;
      form.setFieldsValue({
        name: draft.name ?? '',
        avatarUrl: draft.avatarUrl ?? '',
        roleName: draft.roleName ?? '',
        roleCode: normalizeGeneratedRoleCode(draft.roleCode ?? ''),
        businessBackground: draft.businessBackground ?? '',
        responsibilities: draft.responsibilities ?? '',
      });
      setMode('advanced');
      message.success('已应用生成草稿，请确认后创建');
    } catch {
      message.error('生成结果格式异常，请重新生成');
    }
  };

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate('/agents')} style={{ marginBottom: 16, padding: 0 }}>
        返回列表
      </Button>

      <Card
        title="新建数字员工"
        extra={
          <Space wrap>
            <Button onClick={() => navigate('/agents')}>取消</Button>
            <Button type="primary" icon={<SaveOutlined />} onClick={() => form.submit()} loading={createAgent.isPending}>
              创建
            </Button>
          </Space>
        }
      >
        {mode === 'natural' ? (
          <div style={{ height: '72vh', minHeight: 520 }}>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="描述你需要的数字员工"
              description="生成结果会先成为可编辑草稿。确认草稿后会回填高级表单，只有点击创建才会真正创建数字员工。"
            />
            <div style={{ height: 'calc(100% - 76px)' }}>
              <AiSessionPanel
                key={naturalSessionKey}
                scene="AGENT_CONFIG_GEN"
                bizRefType="ORG"
                bizRefId={0}
                onConfirm={handleGeneratedDraft}
              />
            </div>
          </div>
        ) : (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="高级表单"
              description={<Text type="secondary">请填写必填配置后创建数字员工。</Text>}
            />
            <Form form={form} layout="vertical" onFinish={handleSubmit} style={{ maxWidth: 800 }}>
              <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}> 
                <Input placeholder="如: 前端开发 Agent" maxLength={128} />
              </Form.Item>
              <Form.Item label="头像 URL" name="avatarUrl">
                <Input placeholder="可选" maxLength={512} />
              </Form.Item>
              <Form.Item label="角色名称" name="roleName" rules={[{ required: true, message: '请输入角色名称' }]}> 
                <AutoComplete
                  options={AGENT_ROLE_NAME_OPTIONS}
                  allowClear
                  placeholder="如: 前端开发工程师"
                  maxLength={128}
                  onSelect={handleRoleNameSelect}
                  onChange={handleRoleNameChange}
                />
              </Form.Item>
              <Form.Item label="角色码" name="roleCode" rules={[{ required: true, message: '请输入角色码' }]}> 
                <AutoComplete
                  options={AGENT_ROLE_CODE_OPTIONS}
                  allowClear
                  placeholder="如: FRONTEND_DEV"
                  maxLength={64}
                  onSelect={handleRoleCodeSelect}
                  onChange={handleRoleCodeChange}
                  onBlur={handleRoleCodeBlur}
                  onKeyDown={handleRoleCodeKeyDown}
                />
              </Form.Item>
              <Form.Item label="业务背景" name="businessBackground">
                <TextArea
                  ref={businessBackgroundRef}
                  rows={4}
                  placeholder="描述该员工所在的业务背景..."
                />
              </Form.Item>
              <Form.Item label="工作职责" name="responsibilities">
                <TextArea rows={4} placeholder="描述该员工的核心职责..." />
              </Form.Item>
            </Form>
          </>
        )}
      </Card>
    </div>
  );
}
