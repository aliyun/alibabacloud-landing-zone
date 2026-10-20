import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { type FocusEvent, useRef, type KeyboardEvent, useState } from 'react';
import { Alert, AutoComplete, Button, Card, Form, Input, Space, Typography, message } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { AiSessionPanel } from '@/shared/ui/AiSessionPanel';
import type { AgentConfigGenResult } from '@/shared/types/ai';
import { useCreateAgent } from './hooks';
import type { CreateAgentRequest } from './api';
import { getAgentRoleCodeOptions, getAgentRoleNameOptions, getRoleCodeByName, getRoleNameByCode } from './constants';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;
const { Text } = Typography;

function normalizeGeneratedRoleCode(value: string) {
  return value.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

export function AgentCreatePage() {
  const { t } = useTranslation('agent');
  const navigate = useNavigate();
  const [form] = Form.useForm<CreateAgentRequest>();
  useLocalizedValidation(form);
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
    accessCommand('READ_WRITE', t('actions.create'), async () => {
      try {
        const agent = await createAgent.mutateAsync(values);
        message.success(i18n.t('create.success', { ns: 'agent' }));
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
      message.success(i18n.t('create.draftApplied', { ns: 'agent' }));
    } catch {
      message.error(i18n.t('create.invalidDraft', { ns: 'agent' }));
    }
  };

  return (
    <div>

      <Card
        className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to="/agents" label={t('actions.backList')} /><span>{t('create.title')}</span></span>} />}
        extra={
          <Space wrap>
            <Button onClick={() => navigate('/agents')}>{t('actions.cancel')}</Button>
            <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} onClick={() => form.submit()} loading={createAgent.isPending}>
              {t('actions.createButton')}
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
              message={t('create.describe')}
              description={t('create.draftHint')}
            />
            <div style={{ height: 'calc(100% - 76px)' }}>
              <AiSessionPanel
                key={naturalSessionKey}
                scene="AGENT_CONFIG_GEN"
                bizRefType='ORG'
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
              message={t('create.advanced')}
              description={<Text type="secondary">{t('create.requiredHint')}</Text>}
            />
            <Form form={form} layout="vertical" onFinish={handleSubmit}>
              <Form.Item label={t('fields.name')} name="name" rules={[{ required: true, message: t('validation.name') }]}>
                <Input placeholder={t('placeholders.name')} maxLength={128} />
              </Form.Item>
              <Form.Item label={t('fields.avatar')} name="avatarUrl">
                <Input placeholder={t('placeholders.optional')} maxLength={512} />
              </Form.Item>
              <Form.Item label={t('fields.roleName')} name="roleName" rules={[{ required: true, message: t('validation.roleName') }]}>
                <AutoComplete
                  options={getAgentRoleNameOptions()}
                  allowClear
                  placeholder={t('placeholders.roleName')}
                  maxLength={128}
                  onSelect={handleRoleNameSelect}
                  onChange={handleRoleNameChange}
                />
              </Form.Item>
              <Form.Item label={t('fields.roleCode')} name="roleCode" rules={[{ required: true, message: t('validation.roleCode') }]}>
                <AutoComplete
                  options={getAgentRoleCodeOptions()}
                  allowClear
                  placeholder={t('placeholders.roleCode')}
                  maxLength={64}
                  onSelect={handleRoleCodeSelect}
                  onChange={handleRoleCodeChange}
                  onBlur={handleRoleCodeBlur}
                  onKeyDown={handleRoleCodeKeyDown}
                />
              </Form.Item>
              <Form.Item label="SOUL.md" name="businessBackground">
                <TextArea
                  ref={businessBackgroundRef}
                  rows={4}
                  placeholder={t('placeholders.background')}
                />
              </Form.Item>
              <Form.Item label="AGENT.md" name="responsibilities">
                <TextArea rows={4} placeholder={t('placeholders.responsibilities')} />
              </Form.Item>
            </Form>
          </>
        )}
      </Card>
    </div>
  );
}
