import { useTranslation } from 'react-i18next';
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
  contentMd: string | null;
  saving?: boolean;
  readOnly?: boolean;
  onSave?: (values: { title: string; contentMd: string }) => Promise<void> | void;
}

export function WorkitemContent({ title, contentMd, saving = false, readOnly = false, onSave }: WorkitemContentProps) {
  const { t } = useTranslation('workitem');
  const safeContentMd = contentMd ?? '';
  const accessCommand = useAccessCommand();
  const [editing, setEditing] = useState(false);
  const [draftTitle, setDraftTitle] = useState(title);
  const [draftContent, setDraftContent] = useState(safeContentMd);

  useEffect(() => {
    if (!editing) {
      setDraftTitle(title);
      setDraftContent(safeContentMd);
    }
  }, [safeContentMd, editing, title]);

  const canSave = useMemo(() => {
    return Boolean(draftTitle.trim())
      && (draftTitle.trim() !== title || draftContent !== safeContentMd);
  }, [safeContentMd, draftContent, draftTitle, title]);

  const handleCancel = () => {
    setDraftTitle(title);
    setDraftContent(safeContentMd);
    setEditing(false);
  };

  const handleSave = async () => {
    if (!canSave || !onSave) {
      return;
    }
    accessCommand('READ_WRITE', t('detail.editCommand'), async () => {
      await onSave({ title: draftTitle.trim(), contentMd: draftContent });
      setEditing(false);
    });
  };

  return (
    <div
      data-testid="workitem-content-section"
      style={{
        background: 'var(--aw-panel)',
        border: '1px solid var(--aw-border)',
        borderRadius: 8,
        padding: 16,
        marginBottom: 16,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
        <Text strong style={{ display: 'block', fontSize: 14 }}>
          {t('detail.document')}
        </Text>
        {readOnly ? (
          <Text type="secondary">{t('detail.externallyMaintained')}</Text>
        ) : editing ? (
          <Space>
            <CopyContentMenu contentMd={safeContentMd} />
            <Button icon={<CloseOutlined />} onClick={handleCancel} disabled={saving}>
              {t('detail.cancel')}
            </Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={!canSave}
              onClick={handleSave}
            >
              {t('detail.save')}
            </Button>
          </Space>
        ) : (
          <Space size={4}>
            <CopyContentMenu contentMd={safeContentMd} />
            <Button icon={<EditOutlined />}
              onClick={() => accessCommand('READ_WRITE', t('detail.editCommand'), () => setEditing(true))}>
              {t('detail.edit')}
            </Button>
          </Space>
        )}
      </div>
      {editing ? (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input
            aria-label={t('detail.titleField')}
            value={draftTitle}
            maxLength={200}
            showCount
            onChange={(event) => setDraftTitle(event.target.value)}
          />
          <TextArea
            aria-label={t('detail.bodyField')}
            value={draftContent}
            rows={12}
            autoSize={{ minRows: 10, maxRows: 24 }}
            onChange={(event) => setDraftContent(event.target.value)}
          />
        </Space>
      ) : (
        <MarkdownView content={safeContentMd} />
      )}
    </div>
  );
}
