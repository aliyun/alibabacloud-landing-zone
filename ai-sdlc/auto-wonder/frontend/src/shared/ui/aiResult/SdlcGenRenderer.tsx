import { useTranslation } from 'react-i18next';
import { Input, Select, Button, Space, Typography, Tag, Switch, InputNumber } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { AiResultRendererProps } from './types';
import type { SdlcGenResult, SdlcGenStep, SdlcStepKind } from '@/shared/types/ai';

const { Text } = Typography;
const { TextArea } = Input;


function reindex(steps: SdlcGenStep[]): SdlcGenStep[] {
  return steps.map((s, i) => ({ ...s, order: i + 1 }));
}

function checklistText(step: SdlcGenStep): string {
  return (step.checklist ?? []).join('\n');
}

function passCriteria(step: SdlcGenStep): string {
  const value = step.gatePolicy?.passCriteria;
  return typeof value === 'string' ? value : '';
}

function updateChecklist(text: string): Partial<SdlcGenStep> {
  return {
    checklist: text
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean),
  };
}

function updatePassCriteria(text: string): Partial<SdlcGenStep> {
  return { gatePolicy: { passCriteria: text } };
}

function newStep(order: number): SdlcGenStep {
  return {
    order,
    name: '',
    kind: 'analysis',
    instructionMd: '',
    checklist: [],
    gatePolicy: { passCriteria: '' },
    required: true,
    timeoutSeconds: 600,
    retryBudget: 1,
  };
}

export function SdlcGenRenderer({ value, onChange, disabled }: AiResultRendererProps<SdlcGenResult>) {
  const { t } = useTranslation('shared');
  const KIND_OPTIONS = [
    { value: 'analysis', label: t('sdlc.analysis') },
    { value: 'implementation', label: t('sdlc.implementation') },
    { value: 'test', label: t('sdlc.test') },
    { value: 'review', label: t('sdlc.review') },
    { value: 'artifact', label: t('sdlc.artifact') },
    { value: 'handoff', label: t('sdlc.handoff') },
    { value: 'cleanup', label: t('sdlc.cleanup') },
  ];
  const steps = value.steps ?? [];

  const setSteps = (next: SdlcGenStep[]) => onChange({ ...value, steps: reindex(next) });

  const update = (i: number, patch: Partial<SdlcGenStep>) =>
    setSteps(steps.map((s, idx) => (idx === i ? { ...s, ...patch } : s)));

  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= steps.length) return;
    const next = [...steps];
    [next[i], next[j]] = [next[j], next[i]];
    setSteps(next);
  };

  const remove = (i: number) => setSteps(steps.filter((_, idx) => idx !== i));

  const add = () => setSteps([...steps, newStep(steps.length + 1)]);

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('sdlc.name')}</Text>
        <Input
          value={value.name}
          disabled={disabled}
          onChange={(e) => onChange({ ...value, name: e.target.value })}
        />
      </div>

      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('sdlc.description')}</Text>
        <TextArea
          value={value.description ?? ''}
          disabled={disabled}
          autoSize={{ minRows: 2, maxRows: 4 }}
          onChange={(e) => onChange({ ...value, description: e.target.value })}
        />
      </div>

      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        {steps.map((s, i) => (
          <div
            key={i}
            style={{
              border: '1px solid var(--aw-border)',
              borderRadius: 8,
              padding: 12,
              background: 'var(--aw-panel)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 10 }}>
              <Space wrap>
                <Tag color="blue">{t('sdlc.step', { index: s.order })}</Tag>
                {s.kind && <Tag>{KIND_OPTIONS.find(option => option.value === s.kind)?.label ?? s.kind}</Tag>}
                {s.required === false ? <Tag>{t('sdlc.optional')}</Tag> : <Tag color="green">{t('sdlc.required')}</Tag>}
              </Space>
              {!disabled && (
                <Space size={4}>
                  <Button size="small" type="text" icon={<ArrowUpOutlined />} aria-label={t('sdlc.moveUp', { index: i + 1 })} disabled={i === 0} onClick={() => move(i, -1)} />
                  <Button size="small" type="text" icon={<ArrowDownOutlined />} aria-label={t('sdlc.moveDown', { index: i + 1 })} disabled={i === steps.length - 1} onClick={() => move(i, 1)} />
                  <Button size="small" type="text" danger icon={<DeleteOutlined />} aria-label={t('sdlc.delete', { index: i + 1 })} onClick={() => remove(i)} />
                </Space>
              )}
            </div>

            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Input
                placeholder={t('sdlc.stepName')}
                value={s.name}
                disabled={disabled}
                onChange={(e) => update(i, { name: e.target.value })}
              />
              <Space wrap align="center">
                <Select
                  style={{ width: 140 }}
                  value={s.kind}
                  disabled={disabled}
                  options={KIND_OPTIONS}
                  onChange={(v) => update(i, { kind: v as SdlcStepKind })}
                  aria-label={t('sdlc.kindLabel', { index: i + 1 })}
                />
                <Space size={6}>
                  <Text type="secondary">{t('sdlc.required')}</Text>
                  <Switch
                    checked={s.required !== false}
                    disabled={disabled}
                    onChange={(checked) => update(i, { required: checked })}
                    aria-label={t('sdlc.requiredLabel', { index: i + 1 })}
                  />
                </Space>
                <Space size={6}>
                  <Text type="secondary">{t('sdlc.timeout')}</Text>
                  <InputNumber
                    min={1}
                    precision={0}
                    value={s.timeoutSeconds}
                    disabled={disabled}
                    onChange={(v) => update(i, { timeoutSeconds: v ?? undefined })}
                    aria-label={t('sdlc.timeoutLabel', { index: i + 1 })}
                  />
                </Space>
                <Space size={6}>
                  <Text type="secondary">{t('sdlc.retries')}</Text>
                  <InputNumber
                    min={0}
                    precision={0}
                    value={s.retryBudget}
                    disabled={disabled}
                    onChange={(v) => update(i, { retryBudget: v ?? undefined })}
                    aria-label={t('sdlc.retriesLabel', { index: i + 1 })}
                  />
                </Space>
              </Space>
              <div>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('sdlc.instruction')}</Text>
                <TextArea
                  aria-label={t('sdlc.instructionLabel', { index: i + 1 })}
                  value={s.instructionMd}
                  disabled={disabled}
                  autoSize={{ minRows: 4, maxRows: 10 }}
                  onChange={(e) => update(i, { instructionMd: e.target.value })}
                />
              </div>
              <div>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('sdlc.checklist')}</Text>
                <TextArea
                  aria-label={t('sdlc.checklistLabel', { index: i + 1 })}
                  value={checklistText(s)}
                  disabled={disabled}
                  autoSize={{ minRows: 2, maxRows: 6 }}
                  onChange={(e) => update(i, updateChecklist(e.target.value))}
                />
              </div>
              <div>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('sdlc.criteria')}</Text>
                <TextArea
                  aria-label={t('sdlc.criteriaLabel', { index: i + 1 })}
                  value={passCriteria(s)}
                  disabled={disabled}
                  autoSize={{ minRows: 2, maxRows: 5 }}
                  onChange={(e) => update(i, updatePassCriteria(e.target.value))}
                />
              </div>
            </Space>
          </div>
        ))}
      </Space>

      {!disabled && (
        <Button block type="dashed" icon={<PlusOutlined />} style={{ marginTop: 12 }} onClick={add}>{t('sdlc.add')}</Button>
      )}
    </div>
  );
}
