import { Card, Input, Select, Button, Space, Typography, Empty } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { AiResultRendererProps } from './types';
import type { MemoryImportResult, MemoryImportItem, MemoryItemType } from '@/shared/types/ai';

const { TextArea } = Input;
const { Text } = Typography;

const TYPE_OPTIONS: { value: MemoryItemType; label: MemoryItemType }[] = [
  { value: '项目知识', label: '项目知识' },
  { value: '工程规则', label: '工程规则' },
  { value: '经验', label: '经验' },
  { value: '偏好', label: '偏好' },
  { value: '避坑', label: '避坑' },
];

export function MemoryImportRenderer({ value, onChange, disabled }: AiResultRendererProps<MemoryImportResult>) {
  const items = value.items ?? [];

  const update = (i: number, patch: Partial<MemoryImportItem>) =>
    onChange({ items: items.map((it, idx) => (idx === i ? { ...it, ...patch } : it)) });

  const discard = (i: number) =>
    onChange({ items: items.filter((_, idx) => idx !== i) });

  if (items.length === 0) {
    return <Empty description="没有可导入的记忆条目" />;
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
                aria-label={`丢弃第${i + 1}条`}
                onClick={() => discard(i)}
              >
                丢弃
              </Button>
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
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>标题（必填）</Text>
          <Input
            value={it.title}
            disabled={disabled}
            style={{ marginBottom: 8 }}
            onChange={(e) => update(i, { title: e.target.value })}
          />
          <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>正文</Text>
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
