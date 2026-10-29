import { useMemo, useState } from 'react';
import { Button, message, Select, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

export const MAX_WORKITEM_TAGS = 50;
export const MAX_WORKITEM_TAG_LENGTH = 64;

export type TagDraftError = 'tooMany' | 'tooLong' | null;

export interface TagDraft {
  tags: string[];
  error: TagDraftError;
}

/** 与后端一致的标签规范化：trim、去空、去重（保留首次出现的顺序），再校验单个长度与总数。 */
export function normalizeTagDraft(rawTags: string[]): TagDraft {
  const tags: string[] = [];
  const seen = new Set<string>();
  for (const raw of rawTags) {
    const tag = raw.trim();
    if (!tag || seen.has(tag)) continue;
    seen.add(tag);
    tags.push(tag);
  }
  if (tags.some((tag) => tag.length > MAX_WORKITEM_TAG_LENGTH)) {
    return { tags, error: 'tooLong' };
  }
  if (tags.length > MAX_WORKITEM_TAGS) {
    return { tags, error: 'tooMany' };
  }
  return { tags, error: null };
}

interface WorkitemTagsEditorProps {
  initialTags: string[];
  /** 当前工作空间已使用过的标签，作为输入联想。 */
  options?: string[];
  saving?: boolean;
  onSave: (tags: string[]) => Promise<unknown> | unknown;
  onCancel?: () => void;
  autoFocus?: boolean;
}

export function WorkitemTagsEditor({
  initialTags,
  options = [],
  saving = false,
  onSave,
  onCancel,
  autoFocus = false,
}: WorkitemTagsEditorProps) {
  const { t } = useTranslation('workitem');
  const [draft, setDraft] = useState<string[]>(initialTags);
  const [submitting, setSubmitting] = useState(false);
  const { tags, error } = normalizeTagDraft(draft);
  const busy = saving || submitting;
  const suggestions = useMemo(() => {
    const seen = new Set<string>();
    return [...options, ...initialTags]
      .filter((tag) => {
        if (!tag || seen.has(tag)) return false;
        seen.add(tag);
        return true;
      })
      .map((tag) => ({ value: tag, label: tag }));
  }, [options, initialTags]);

  const errorText = error === 'tooMany'
    ? t('tags.tooMany', { max: MAX_WORKITEM_TAGS })
    : error === 'tooLong'
      ? t('tags.tooLong', { max: MAX_WORKITEM_TAG_LENGTH })
      : null;

  const handleSave = async () => {
    if (error || busy) return;
    setSubmitting(true);
    try {
      await onSave(tags);
    } catch (failure) {
      // 保存失败时保留草稿，用户修正后可以直接重试。
      message.error((failure as Error)?.message || t('tags.saveFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div data-testid="workitem-tags-editor" style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
      <Select
        mode="tags"
        value={draft}
        onChange={setDraft}
        options={suggestions}
        tokenSeparators={[',', '，']}
        placeholder={t('tags.placeholder')}
        aria-label={t('tags.selectAria')}
        autoFocus={autoFocus}
        style={{ width: '100%' }}
      />
      <Typography.Text type={errorText ? 'danger' : 'secondary'} style={{ fontSize: 12 }}>
        {errorText ?? t('tags.count', { count: tags.length, max: MAX_WORKITEM_TAGS })}
      </Typography.Text>
      <Space>
        <Button
          type="primary"
          size="small"
          loading={busy}
          disabled={Boolean(error)}
          aria-label={t('tags.save')}
          onClick={() => { void handleSave(); }}
        >
          {t('tags.save')}
        </Button>
        {onCancel ? (
          <Button size="small" onClick={onCancel}>{t('tags.cancel')}</Button>
        ) : null}
      </Space>
    </div>
  );
}
