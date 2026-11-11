import { useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Modal, Select, Space, Typography, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiClient } from '@/shared/api/client';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { listAllSkills, listCategories, type Skill, type SkillConnectionTestResult } from '@/features/skill/api';

const CATEGORY = 'browser-use';
export interface BrowserSelection { skillId?: number | null; url?: string | null; codeOnly: boolean }
export function browserSelectionValid(value: BrowserSelection | null): boolean {
  if (!value) return false;
  if (value.codeOnly) return true;
  try { const url = new URL(value.url || ''); return !!value.skillId && ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password; }
  catch { return false; }
}

export function PrdBrowserSettings({ workitemId, agentId, value, onChange, disabled }: {
  workitemId: string; agentId?: number; value: BrowserSelection | null;
  onChange: (value: BrowserSelection) => void; disabled: boolean;
}) {
  const { t } = useTranslation('prd');
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [checked, setChecked] = useState<{ key: string; success: boolean } | null>(null);
  const [form] = Form.useForm<{ name: string; url: string; token: string }>();
  useLocalizedValidation(form);
  const environments = useQuery({ queryKey: ['prd-browser-mcps'], queryFn: async () => {
    const [categories, skills] = await Promise.all([listCategories(), listAllSkills('MCP')]);
    const category = categories.find(c => c.parentId == null && c.name === CATEGORY);
    return skills.filter(s => s.categoryId === category?.id && category != null).filter(s => {
      try { return JSON.parse(s.installSpec).transport === 'http'; } catch { return false; }
    });
  } });
  const current = value ?? { codeOnly: false };
  const checkKey = JSON.stringify([workitemId, agentId, current]);
  const result = checked?.key === checkKey ? checked : null;
  const change = (next: Partial<BrowserSelection>) => { setChecked(null); onChange({ ...current, ...next }); };
  const save = async () => {
    let fields: { name: string; url: string; token: string };
    try { fields = await form.validateFields(); } catch { return; }
    setSaving(true);
    try {
      const skill = (await apiClient.post<Skill>('/api/skills/browser', fields)).data;
      await client.invalidateQueries({ queryKey: ['prd-browser-mcps'] });
      change({ skillId: skill.id, codeOnly: false });
      form.resetFields(); setOpen(false); message.success(t('browser.saved'));
    } catch { message.error(t('browser.saveFailed')); }
    finally { setSaving(false); }
  };
  const check = async () => {
    setChecking(true); setChecked(null);
    try {
      const response = (await apiClient.post<{ executorId: number; result: SkillConnectionTestResult }>(
        `/api/workitems/${workitemId}/prd/conversation/browser-check`, { agentId, browser: current })).data;
      const names = new Set(response.result.tools?.map(tool => tool.name));
      const success = response.result.success && ['browser_navigate', 'browser_snapshot', 'browser_click', 'browser_take_screenshot'].every(name => names.has(name));
      setChecked({ key: checkKey, success });
    } catch { setChecked({ key: checkKey, success: false }); }
    finally { setChecking(false); }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Typography.Text strong>{t('browser.title')}</Typography.Text>
    {environments.isError && <Alert type="error" message={t('browser.loadFailed')} action={<Button onClick={() => void environments.refetch()}>{t('preview.retry')}</Button>} />}
    {!environments.isPending && !environments.isError && !environments.data?.length && <Alert type="info" message={t('browser.missing')} />}
    <Checkbox checked={current.codeOnly} disabled={disabled || checking} onChange={event => change({ codeOnly: event.target.checked })}>{t('browser.codeOnly')}</Checkbox>
    {current.codeOnly ? <Alert type="warning" message={t('browser.codeOnlyHint')} /> : <>
      <Select aria-label={t('browser.environment')} placeholder={t('browser.environment')} style={{ width: '100%' }}
        value={current.skillId ?? undefined} loading={environments.isFetching} disabled={disabled || checking}
        onChange={skillId => change({ skillId })} options={environments.data?.map(s => ({ value: s.id, label: s.name }))} />
      {current.skillId && environments.isSuccess && !environments.data.some(s => s.id === current.skillId) && <Alert type="warning" message={t('browser.unavailable')} />}
      <Input aria-label={t('browser.appUrl')} placeholder={t('browser.appUrl')} value={current.url || ''} maxLength={2048}
        disabled={disabled || checking} onChange={event => change({ url: event.target.value })} />
      <Space>
        <Button disabled={disabled || checking} onClick={() => setOpen(true)}>{t('browser.add')}</Button>
        <Button loading={checking} disabled={disabled || !agentId || !browserSelectionValid(current)} onClick={() => void check()}>{t('browser.check')}</Button>
      </Space>
      <Typography.Text type="secondary">{t('browser.loginHint')}</Typography.Text>
      {result && <Alert type={result.success ? 'success' : 'warning'} message={t(result.success ? 'browser.checkPassed' : 'browser.checkFailed')} />}
    </>}
    <Modal title={t('browser.add')} open={open} confirmLoading={saving} onOk={() => void save()} onCancel={() => { if (!saving) { setOpen(false); form.resetFields(); } }}
      okText={t('browser.save')} cancelText={t('browser.cancel')}>
      <Form form={form} layout="vertical">
        <Form.Item name="name" label={t('browser.name')} rules={[{ required: true, whitespace: true, message: t('browser.required') }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="url" label={t('browser.mcpUrl')} rules={[{ required: true, message: t('browser.required') }, { validator: (_, url) => browserSelectionValid({ skillId: 1, url, codeOnly: false }) ? Promise.resolve() : Promise.reject(new Error(t('browser.invalidUrl'))) }]}><Input maxLength={2048} /></Form.Item>
        <Form.Item name="token" label={t('browser.token')} rules={[{ required: true, whitespace: true, message: t('browser.required') }]}><Input.Password autoComplete="new-password" maxLength={4000} /></Form.Item>
        <Typography.Text type="secondary">{t('browser.categoryHint', { category: CATEGORY })}</Typography.Text>
      </Form>
    </Modal>
  </Space>;
}
