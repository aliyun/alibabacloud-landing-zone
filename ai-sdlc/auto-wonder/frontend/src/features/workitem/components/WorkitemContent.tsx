import { useEffect, useMemo, useState } from 'react';
import { Button, Input, Space, Typography } from 'antd';
import { CloseOutlined, EditOutlined, SaveOutlined } from '@ant-design/icons';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { CopyContentMenu } from '@/shared/ui/CopyContentMenu';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const { TextArea } = Input;

interface WorkitemContentProps {
  title: string;
  contentMd: string;
  saving?: boolean;
  onSave?: (values: { title: string; contentMd: string }) => Promise<void> | void;
}

export function WorkitemContent({ title, contentMd, saving = false, onSave }: WorkitemContentProps) {
  const accessCommand = useAccessCommand();
  const [editing, setEditing] = useState(false);
  const [draftTitle, setDraftTitle] = useState(title);
  const [draftContent, setDraftContent] = useState(contentMd);

  useEffect(() => {
    if (!editing) {
      setDraftTitle(title);
      setDraftContent(contentMd);
    }
  }, [contentMd, editing, title]);

  const canSave = useMemo(() => {
    return Boolean(draftTitle.trim())
      && (draftTitle.trim() !== title || draftContent !== contentMd);
  }, [contentMd, draftContent, draftTitle, title]);

  const handleCancel = () => {
    setDraftTitle(title);
    setDraftContent(contentMd);
    setEditing(false);
  };

  const handleSave = async () => {
    if (!canSave || !onSave) {
      return;
    }
    accessCommand('READ_WRITE', '编辑工单内容', async () => {
      await onSave({ title: draftTitle.trim(), contentMd: draftContent });
      setEditing(false);
    });
  };

  return (
    <div
      data-testid="workitem-content-section"
      style={{
        background: '#fff',
        border: '1px solid #e5e7eb',
        borderRadius: 8,
        padding: 16,
        marginBottom: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
        <Text strong style={{ display: 'block', fontSize: 14 }}>
          需求/设计文档
        </Text>
        {editing ? (
          <Space>
            <CopyContentMenu contentMd={contentMd} />
            <Button icon={<CloseOutlined />} onClick={handleCancel} disabled={saving}>
              取消
            </Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={!canSave}
              onClick={handleSave}
            >
              保存
            </Button>
          </Space>
        ) : (
          <Space size={4}>
            <CopyContentMenu contentMd={contentMd} />
            <Button icon={<EditOutlined />}
              onClick={() => accessCommand('READ_WRITE', '编辑工单内容', () => setEditing(true))}>
              编辑
            </Button>
          </Space>
        )}
      </div>
      {editing ? (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            aria-label="工单标题"
            value={draftTitle}
            maxLength={200}
            showCount
            onChange={(event) => setDraftTitle(event.target.value)}
          />
          <TextArea
            aria-label="工单正文"
            value={draftContent}
            rows={12}
            autoSize={{ minRows: 10, maxRows: 24 }}
            onChange={(event) => setDraftContent(event.target.value)}
          />
        </Space>
      ) : (
        <MarkdownView content={contentMd} />
      )}
    </div>
  );
}
