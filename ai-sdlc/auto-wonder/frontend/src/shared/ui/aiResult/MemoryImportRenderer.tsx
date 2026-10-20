import { useTranslation } from 'react-i18next';
import { Card, Input, Select, Button, Space, Typography, Empty } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { AiResultRendererProps } from './types';
import type { MemoryImportResult, MemoryImportItem, MemoryItemType } from '@/shared/types/ai';

const { TextArea } = Input;
const { Text } = Typography;


export function MemoryImportRenderer({ value, onChange, disabled }: AiResultRendererProps<MemoryImportResult>) {
  const { t } = useTranslation('shared');
  const TYPE_OPTIONS: { value: MemoryItemType; label: string }[] = [
    { value: '项目知识', label: t('memory.project') },
    { value: '工程规则', label: t('memory.rules') },
    { value: '经验', label: t('memory.experience') },
    { value: '偏好', label: t('memory.preferences') },
    { value: '避坑', label: t('memory.pitfalls') },
  ];
  const items = value.items ?? [];

  const update = (i: number, patch: Partial<MemoryImportItem>) =>
    onChange({ items: items.map((it, idx) => (idx === i ? { ...it, ...patch } : it)) });

  const discard = (i: number) =>
    onChange({ items: items.filter((_, idx) => idx !== i) });

  if (items.length === 0) {
    return <Empty description={t('memory.empty')} />;
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {items.map((it, i) => (
        <Card
          key={i}
          size="small"
          extra={
            !disabled && (
              <Button
                size="small"
                danger
                type="text"
                icon={<DeleteOutlined />}
                aria-label={t('memory.discardItem', { index: i + 1 })}
                onClick={() => discard(i)}
              >{t('memory.discard')}</Button>
            )
          }
          title={
            <Select
              size="small"
              style={{ width: 120 }}
              value={it.type}
              disabled={disabled}
              options={TYPE_OPTIONS}
              onChange={(v) => update(i, { type: v })}
            />
          }
        >
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('memory.title')}</Text>
          <Input
            value={it.title}
            disabled={disabled}
            style={{ marginBottom: 8 }}
            onChange={(e) => update(i, { title: e.target.value })}
          />
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>{t('memory.body')}</Text>
          <TextArea
            value={it.contentMd}
            disabled={disabled}
            autoSize={{ minRows: 2, maxRows: 8 }}
            onChange={(e) => update(i, { contentMd: e.target.value })}
          />
        </Card>
      ))}
    </Space>
  );
}
