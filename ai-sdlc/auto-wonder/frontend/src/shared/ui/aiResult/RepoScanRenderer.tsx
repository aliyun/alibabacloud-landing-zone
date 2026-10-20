import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useState, useMemo } from 'react';
import { Input, Tag, Space, Typography, Tabs } from 'antd';
import { MarkdownView } from '../MarkdownView';
import type { AiResultRendererProps } from './types';
import type { RepoScanResult } from '@/shared/types/ai';

const { TextArea } = Input;
const { Text } = Typography;

type TagField = 'keyBusiness' | 'upstreams' | 'downstreams';

function toStringArray(val: unknown): string[] {
  if (Array.isArray(val)) return val.filter((v): v is string => typeof v === 'string' && v.trim() !== '');
  if (typeof val === 'string' && val.trim()) return val.split(/[,，、\n]+/).map(s => s.trim()).filter(Boolean);
  return [];
}

function normalizeResult(raw: unknown): RepoScanResult {
  const obj = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  let purpose = '';
  if (typeof obj.purpose === 'string') {
    purpose = obj.purpose;
  } else if (obj.purpose && typeof obj.purpose === 'object') {
    const p = obj.purpose as Record<string, unknown>;
    purpose = [p.role, p.goal].filter(v => typeof v === 'string' && v).join(' — ');
  }
  return {
    purpose,
    keyBusiness: toStringArray(obj.keyBusiness),
    upstreams: toStringArray(obj.upstreams),
    downstreams: toStringArray(obj.downstreams),
    summaryMd: typeof obj.summaryMd === 'string' ? obj.summaryMd : '',
  };
}

const TAG_LABELS: Record<TagField, string> = {
  get keyBusiness() { return i18n.t('shared:repo.business'); },
  get upstreams() { return i18n.t('shared:repo.upstreams'); },
  get downstreams() { return i18n.t('shared:repo.downstreams'); },
};

function TagListEdit({
  label,
  values,
  disabled,
  onChange,
}: {
  label: string;
  values: string[];
  disabled?: boolean;
  onChange: (next: string[]) => void;
}) {
  const { t } = useTranslation('shared');
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState('');

  const commit = () => {
    const v = draft.trim();
    if (v) onChange([...values, v]);
    setDraft('');
    setAdding(false);
  };

  return (
    <div style={{ marginBottom: 12 }}>
      <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{label}</Text>
      <Space size={[4, 4]} wrap>
        {values.map((v, i) => (
          <Tag
            key={`${v}-${i}`}
            closable={!disabled}
            onClose={() => onChange(values.filter((_, idx) => idx !== i))}
          >
            {v}
          </Tag>
        ))}
        {!disabled && (adding ? (
          <Input
            size="small"
            autoFocus
            style={{ width: 120 }}
            value={draft}
            aria-label={t('repo.addTag', { label })}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            onPressEnter={commit}
          />
        ) : (
          <Tag onClick={() => setAdding(true)} style={{ cursor: 'pointer', borderStyle: 'dashed' }}>
            {t('repo.addTagLabel', { label })}
          </Tag>
        ))}
      </Space>
    </div>
  );
}

export function RepoScanRenderer({ value: rawValue, onChange, disabled }: AiResultRendererProps<RepoScanResult>) {
  const { t } = useTranslation('shared');
  const value = useMemo(() => normalizeResult(rawValue), [rawValue]);
  const set = <K extends keyof RepoScanResult>(k: K, v: RepoScanResult[K]) =>
    onChange({ ...value, [k]: v });

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('repo.purpose')}</Text>
        <Input
          value={value.purpose}
          disabled={disabled}
          placeholder={t('repo.purposePlaceholder')}
          onChange={(e) => set('purpose', e.target.value)}
        />
      </div>

      {(Object.keys(TAG_LABELS) as TagField[]).map((f) => (
        <TagListEdit
          key={f}
          label={TAG_LABELS[f]}
          values={value[f]}
          disabled={disabled}
          onChange={(next) => set(f, next)}
        />
      ))}

      <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('repo.summary')}</Text>
      <Tabs
        size="small"
        items={[
          {
            key: 'edit',
            label: t('repo.edit'),
            children: (
              <TextArea
                value={value.summaryMd}
                disabled={disabled}
                autoSize={{ minRows: 6, maxRows: 16 }}
                onChange={(e) => set('summaryMd', e.target.value)}
              />
            ),
          },
          {
            key: 'preview',
            label: t('repo.preview'),
            children: <MarkdownView content={value.summaryMd} />,
          },
        ]}
      />
    </div>
  );
}
