import { useEffect, useMemo, useRef } from 'react';
import { Alert, Button, Form, Input, InputNumber, Select, Space, Switch, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/shared/auth/store';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { ApiError } from '@/shared/types/common';
import { getModelUpgradePolicy, updateModelUpgradePolicy, getExecutorModelCatalog } from './api';
import type { ExecutorVO, ModelUpgradePolicy } from './api';
import { qoderProviderForClientKind, resolveQoderModelOptions } from './qoderOptions';

export function ModelUpgradePolicyEditor({ executor }: { executor: ExecutorVO }) {
  const { t } = useTranslation('executor');
  const [form] = Form.useForm<{ modelUpgradePolicy: ModelUpgradePolicy }>();
  useLocalizedValidation(form, true);
  const queryClient = useQueryClient();
  const accessLevel = useAuthStore(state => state.accessLevel);
  const readOnlyDomains = useAuthStore(state => state.readOnlyDomains);
  // 与后端一致：读写级即可编辑，角色勾选 EXECUTOR 只读后收回。
  const canEdit = (accessLevel === 'READ_WRITE' || accessLevel === 'ADMIN')
    && !readOnlyDomains.includes('EXECUTOR');
  const policyQuery = useQuery({ queryKey: ['executor-model-upgrade-policy', executor.id], queryFn: () => getModelUpgradePolicy(executor.id), retry: false });
  const provider = qoderProviderForClientKind(executor.clientKind);
  const catalog = useQuery({ queryKey: ['executor-model-catalog', provider], queryFn: () => getExecutorModelCatalog(provider!), enabled: Boolean(provider), retry: false });
  const options = useMemo(() => resolveQoderModelOptions(catalog.isError ? undefined : catalog.data?.models), [catalog.data, catalog.isError, t]);
  const initialized = useRef(false);
  const policy = Form.useWatch('modelUpgradePolicy', form);
  const unavailable = Boolean(provider && policy?.levels?.some(level => level.model && !options.some(option => option.value === level.model)));
  useEffect(() => {
    if (policyQuery.data && !initialized.current) {
      form.setFieldsValue({ modelUpgradePolicy: policyQuery.data.modelUpgradePolicy ?? { enabled: false, levels: [] } });
      initialized.current = true;
    }
  }, [policyQuery.data, form]);
  const save = useMutation({
    mutationFn: (value: ModelUpgradePolicy) => updateModelUpgradePolicy(executor.id, value, policyQuery.data!.version),
    onSuccess: data => {
      form.setFieldsValue({ modelUpgradePolicy: data.modelUpgradePolicy ?? { enabled: false, levels: [] } });
      queryClient.setQueryData(['executor-model-upgrade-policy', executor.id], data);
      queryClient.invalidateQueries({ queryKey: ['executor-launch-command', executor.id] });
      message.success(t('modelUpgradePolicySaved'));
    },
    onError: async error => {
      if (error instanceof ApiError && error.code === '17005') {
        message.error(t('configChanged'));
        await policyQuery.refetch();
      } else message.error(error instanceof ApiError ? error.message : t('modelUpgradePolicySaveFailed'));
    },
  });
  return <section aria-label={t('modelUpgradePolicyLabel')}>
    <Typography.Title level={5}>{t('modelUpgradePolicyLabel')}</Typography.Title>
    <Typography.Paragraph type="secondary">{t('modelUpgradePolicyEffective')}</Typography.Paragraph>
    {policyQuery.isError && <Alert type="error" showIcon message={t('modelUpgradePolicyReadFailed')}
      action={<Button onClick={() => policyQuery.refetch()}>{t('modelUpgradePolicyRetry')}</Button>} />}
    {policyQuery.data && <>
      <Typography.Paragraph>{t('modelUpgradePolicyDefault', { model: options.find(option => option.value === policyQuery.data.model)?.label ?? policyQuery.data.model ?? executor.modelName ?? executor.model ?? t('notReported') })}</Typography.Paragraph>
      {unavailable && <Alert type="warning" showIcon message={t('modelUpgradePolicyUnavailable')} style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical" disabled={!canEdit || save.isPending}
        onFinish={values => save.mutate(values.modelUpgradePolicy.enabled ? values.modelUpgradePolicy : { enabled: false, levels: (values.modelUpgradePolicy.levels ?? []).filter(level => Number.isInteger(level.entryCount) && level.entryCount >= 2 && level.model) })}>
        <Form.Item label={t('modelUpgradePolicyLabel')} name={['modelUpgradePolicy', 'enabled']} valuePropName="checked" extra={t('modelUpgradePolicyHelp')}>
          <Switch onChange={enabled => {
            if (enabled && !form.getFieldValue(['modelUpgradePolicy', 'levels'])?.length)
              form.setFieldValue(['modelUpgradePolicy', 'levels'], [{ entryCount: 2 }]);
          }} />
        </Form.Item>
        <Form.List name={['modelUpgradePolicy', 'levels']} rules={[{ validator: async (_, levels) => {
          if (!form.getFieldValue(['modelUpgradePolicy', 'enabled'])) return;
          let previous = 1;
          const selected = new Set<string>();
          if (!levels?.length) throw new Error(t('modelUpgradePolicyValidation'));
          for (const level of levels) {
            if (!Number.isInteger(level?.entryCount) || level.entryCount <= previous || !level.model || selected.has(level.model))
              throw new Error(t('modelUpgradePolicyValidation'));
            if (provider && !options.some(option => option.value === level.model)) throw new Error(t('modelUpgradePolicyUnavailable'));
            previous = level.entryCount; selected.add(level.model);
          }
        } }]}>
          {(fields, { add, remove }, { errors }) => <>
            {fields.map(field => <div key={field.key} style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'baseline' }}>
              <Form.Item name={[field.name, 'entryCount']} label={t('modelUpgradePolicyEntryCount')}
                style={{ flex: '0 1 130px' }} rules={policy?.enabled ? [{ required: true, type: 'integer', min: 2, message: t('modelUpgradePolicyValidation') }] : []}>
                <InputNumber min={2} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name={[field.name, 'model']} label={t('modelUpgradePolicyModel')} style={{ flex: '1 1 170px' }}
                rules={policy?.enabled ? [{ required: true, message: t('modelUpgradePolicySelect') }] : []}>
                {provider ? <Select showSearch optionFilterProp="label" options={options} loading={catalog.isLoading}
                  placeholder={t('modelUpgradePolicySelect')} /> : <Input placeholder={t('modelUpgradePolicyModelPlaceholder')} />}
              </Form.Item>
              <Button onClick={() => remove(field.name)}>{t('modelUpgradePolicyRemove')}</Button>
            </div>)}
            <Button onClick={() => {
              const levels = form.getFieldValue(['modelUpgradePolicy', 'levels']) ?? [];
              add({ entryCount: Math.max(2, Number(levels.at(-1)?.entryCount ?? 1) + 1) });
            }}>{t('modelUpgradePolicyAdd')}</Button>
            <Form.ErrorList errors={errors} />
          </>}
        </Form.List>
        {canEdit && <Space style={{ marginTop: 16 }}>
          <Button onClick={() => form.setFieldsValue({ modelUpgradePolicy: policyQuery.data!.modelUpgradePolicy ?? { enabled: false, levels: [] } })}>{t('modelUpgradePolicyCancel')}</Button>
          <Button type="primary" htmlType="submit" loading={save.isPending}>{t('modelUpgradePolicySave')}</Button>
        </Space>}
      </Form>
    </>}
  </section>;
}
