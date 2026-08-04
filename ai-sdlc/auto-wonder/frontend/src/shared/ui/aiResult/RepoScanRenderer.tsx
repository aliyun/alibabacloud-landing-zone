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
  keyBusiness: '关键业务',
  upstreams: '上游',
  downstreams: '下游',
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
            aria-label={`新增${label}`}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            onPressEnter={commit}
          />
        ) : (
          <Tag onClick={() => setAdding(true)} style={{ cursor: 'pointer', borderStyle: 'dashed' }}>
            {`+ ${label}`}
          </Tag>
        ))}
      </Space>
    </div>
  );
}

export function RepoScanRenderer({ value: rawValue, onChange, disabled }: AiResultRendererProps<RepoScanResult>) {
  const value = useMemo(() => normalizeResult(rawValue), [rawValue]);
  const set = <K extends keyof RepoScanResult>(k: K, v: RepoScanResult[K]) =>
    onChange({ ...value, [k]: v });

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>作用（必填）</Text>
        <Input
          value={value.purpose}
          disabled={disabled}
          placeholder="这个仓库的职责"
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

      <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>结论正文（Markdown）</Text>
      <Tabs
        size="small"
        items={[
          {
            key: 'edit',
            label: '编辑',
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
            label: '预览',
            children: <MarkdownView content={value.summaryMd} />,
          },
        ]}
      />
    </div>
  );
}
