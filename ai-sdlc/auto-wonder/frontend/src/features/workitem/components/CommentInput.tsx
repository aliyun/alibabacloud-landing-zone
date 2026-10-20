import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useEffect, useMemo, useRef, useState, type ClipboardEvent, type DragEvent, type CSSProperties, type ChangeEvent, type KeyboardEvent } from 'react';
import { Alert, Button, Input, Space, message } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { PaperClipOutlined, SendOutlined } from '@ant-design/icons';
import { useAddComment, useMentionCandidates, useUploadRequirementDocuments } from '../hooks';
import type { Artifact, Participant } from '@/shared/types/workitem';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  DOCUMENT_ACCEPT_ATTRIBUTE, DOCUMENT_UPLOAD_MESSAGES, documentsFromDataTransfer,
  filesFromClipboard, pastedFileForUpload, transferContainsFiles, validateDocumentSelection,
} from '@/shared/lib/documentUpload';
import { CommentAttachment, commentAttachmentMarkdown, type CommentAttachmentDraft } from './CommentAttachment';

interface CommentInputProps {
  workitemId: string;
  participants?: Participant[];
  documents?: Artifact[];
}

const MENTION_QUERY_DEBOUNCE_MS = 200;

const mentionOptionHighlightStyle: CSSProperties = {
  color: 'var(--aw-accent-text)',
  background: 'rgba(var(--aw-accent-rgb),.10)',
  borderRadius: 4,
  padding: '0 4px',
  fontWeight: 600,
};

const composerTextStyle: CSSProperties = {
  fontFamily: 'inherit',
  fontSize: 14,
  lineHeight: '22px',
  letterSpacing: 0,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'break-word',
};

const mentionMenuStyle: CSSProperties = {
  position: 'absolute',
  left: 0,
  right: 0,
  bottom: '100%',
  marginBottom: 6,
  padding: '4px 0',
  background: 'var(--aw-glass)',
  backdropFilter: 'blur(18px)',
  border: '1px solid var(--aw-border)',
  borderRadius: 6,
  boxShadow: '0 6px 16px rgba(0, 0, 0, 0.08)',
  zIndex: 20,
  maxHeight: 350, // ≈10 个候选项高度，超出滚动查看
  overflowY: 'auto',
};

const mentionMenuItemStyle: CSSProperties = {
  display: 'block',
  width: '100%',
  padding: '6px 12px',
  border: 0,
  background: 'transparent',
  color: 'var(--aw-text)',
  textAlign: 'left',
  cursor: 'pointer',
};

const activeMentionMenuItemStyle: CSSProperties = {
  background: 'rgba(var(--aw-accent-rgb),.10)',
};

function findActiveMentionQuery(value: string, caretIndex: number): string | null {
  const textBeforeCaret = value.slice(0, caretIndex);
  const mentionStart = textBeforeCaret.lastIndexOf('@');

  if (mentionStart < 0) return null;

  const query = textBeforeCaret.slice(mentionStart + 1);
  if (/\s/.test(query)) return null;

  return query;
}

export function CommentInput({ workitemId, participants = [], documents = [] }: CommentInputProps) {
  const { t } = useTranslation('workitem');
  const [content, setContent] = useState('');
  const [targetAgentIds, setTargetAgentIds] = useState<number[]>([]);
  const [targetHumanIds, setTargetHumanIds] = useState<number[]>([]);
  const [selectedMentionNames, setSelectedMentionNames] = useState<Map<string, string>>(new Map());
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const mentionQueryRef = useRef<string | null>(null);
  const [debouncedMentionQuery, setDebouncedMentionQuery] = useState<string | null>(null);
  const [activeMentionIndex, setActiveMentionIndex] = useState(0);
  const [isExpanded, setIsExpanded] = useState(false);
  const [deliveryClosed, setDeliveryClosed] = useState(false);
  const textAreaRef = useRef<TextAreaRef>(null);
  const { mutate, isPending } = useAddComment();
  const { data: mentionCandidates } = useMentionCandidates(workitemId, debouncedMentionQuery);
  const accessCommand = useAccessCommand();
  const uploadMutation = useUploadRequirementDocuments(workitemId);
  const [attachments, setAttachments] = useState<CommentAttachmentDraft[]>([]);
  const [sending, setSending] = useState(false);
  const sendLock = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragActive, setDragActive] = useState(false);
  const dragDepth = useRef(0);
  const busy = isPending || sending;

  const selectFiles = (files: File[]) => {
    if (sendLock.current || isPending || files.length === 0) return;
    accessCommand('READ_WRITE', t('comment.attachFiles'), () => {
      // Uploaded attachments may already be in the refreshed document query. Count them once.
      const saved = new Map(documents.map((document) => [String(document.id), document]));
      attachments.forEach(({ artifact }) => { if (artifact) saved.set(String(artifact.id), artifact); });
      const pending = attachments.filter(({ artifact }) => !artifact).map(({ file }) => file);
      const existing = [...saved.values()];
      const verdict = validateDocumentSelection(files, {
        existingCount: existing.length + pending.length,
        existingNames: [...existing.map((document) => document.name.replace(/^requirements\//, '')), ...pending.map((file) => file.name)],
        existingTotalBytes: existing.reduce((total, document) => total + (document.size ?? 0), 0)
          + pending.reduce((total, file) => total + file.size, 0),
      });
      if (!verdict.ok) {
        message.error(verdict.message);
        return;
      }
      setAttachments((current) => [...current, ...files.map((file) => ({ file }))]);
    });
  };

  const handlePaste = (event: ClipboardEvent<HTMLDivElement>) => {
    const files = filesFromClipboard(event.clipboardData);
    if (files.length === 0) return;
    event.preventDefault();
    selectFiles(files.map((file) => pastedFileForUpload(file)));
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    if (!transferContainsFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepth.current = 0;
    setDragActive(false);
    if (busy) return;
    const { files, containsDirectory } = documentsFromDataTransfer(event.dataTransfer);
    if (containsDirectory || files.length === 0) {
      message.error(containsDirectory ? DOCUMENT_UPLOAD_MESSAGES.directory : DOCUMENT_UPLOAD_MESSAGES.noLocalFile);
      return;
    }
    selectFiles(files);
  };

  const finishSending = () => {
    sendLock.current = false;
    setSending(false);
  };

  const allMentionCandidates = useMemo(
    () => mentionCandidates ?? participants,
    [mentionCandidates, participants],
  );

  // 候选人请求按防抖合并；防抖窗口内 mentionOptions 继续用上一批候选人做本地过滤，菜单不会闪空。
  useEffect(() => {
    if (mentionQuery === null) {
      setDebouncedMentionQuery(null);
      return;
    }
    const timer = window.setTimeout(() => setDebouncedMentionQuery(mentionQuery), MENTION_QUERY_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [mentionQuery]);

  const participantByTarget = useMemo(() => {
    const byTarget = new Map<string, Participant>();
    [...participants, ...allMentionCandidates].forEach((item) => {
      byTarget.set(`${item.isAgent ? 'AGENT' : 'HUMAN'}:${Number(item.userId)}`, item);
    });
    return byTarget;
  }, [allMentionCandidates, participants]);

  const mentionOptions = useMemo(() => {
    const normalizedQuery = mentionQuery?.toLowerCase() ?? '';

    return allMentionCandidates
      .filter((item) => typeof item.name === 'string' && item.name.trim() !== '')
      .filter((item) => item.name.toLowerCase().includes(normalizedQuery)
        || String(item.displayId ?? item.userId).toLowerCase().includes(normalizedQuery))
      .map((item) => {
        const targetType: 'AGENT' | 'HUMAN' = item.isAgent ? 'AGENT' : 'HUMAN';
        const targetId = Number(item.userId);
        const kindLabel = item.isAgent ? t('comment.agent') : t('comment.human');
        return {
          value: item.name,
          label: (
            <span>
              <span style={mentionOptionHighlightStyle}>
                @{item.name}
              </span>
              <span style={{ marginLeft: 6, color: item.isAgent ? 'var(--aw-warning)' : 'var(--aw-accent-text)' }}>{kindLabel}</span>
              {item.displayId ? <span style={{ marginLeft: 6, color: 'var(--aw-muted)' }}>{item.displayId}</span> : null}
              {item.isAgent && !item.online ? t('comment.offline') : ''}
            </span>
          ),
          targetId,
          targetType,
        };
      });
  }, [allMentionCandidates, mentionQuery, t]);

  // keyup/click 常推导出与当前相同的查询；先比对 ref 直接跳过 setState，避免无意义重渲染。
  const applyMentionQuery = (nextQuery: string | null) => {
    if (mentionQueryRef.current === nextQuery) return;
    mentionQueryRef.current = nextQuery;
    setMentionQuery(nextQuery);
  };

  const updateMentionQuery = (value: string, caretIndex: number) => {
    applyMentionQuery(findActiveMentionQuery(value, caretIndex));
  };

  const clearMentionQuery = () => {
    applyMentionQuery(null);
  };

  useEffect(() => {
    setActiveMentionIndex(0);
  }, [mentionQuery]);

  useEffect(() => {
    setActiveMentionIndex((current) => {
      if (mentionOptions.length === 0) return 0;
      return Math.min(current, mentionOptions.length - 1);
    });
  }, [mentionOptions.length]);

  const hasMentionText = (value: string, targetType: 'AGENT' | 'HUMAN', targetId: number) => {
    const key = `${targetType}:${targetId}`;
    const name = selectedMentionNames.get(key) ?? participantByTarget.get(key)?.name;
    return name != null && value.includes(`@${name}`);
  };

  const syncSelectedMentions = (value: string) => {
    // filter 不改变相对顺序，长度不变即结果与原数组等价；返回原引用可避免每次按键都触发一次状态更新。
    setTargetAgentIds((current) => {
      const next = current.filter((agentId) => hasMentionText(value, 'AGENT', agentId));
      return next.length === current.length ? current : next;
    });
    setTargetHumanIds((current) => {
      const next = current.filter((humanId) => hasMentionText(value, 'HUMAN', humanId));
      return next.length === current.length ? current : next;
    });
  };

  const handleSend = () => {
    const trimmed = content.trim();
    if (sendLock.current || isPending || (!trimmed && attachments.length === 0)) return;

    accessCommand('READ_WRITE', t('comment.sendCommand'), async () => {
      sendLock.current = true;
      setSending(true);
      let prepared = attachments;
      const pending = attachments.filter(({ artifact }) => !artifact);
      if (pending.length > 0) {
        try {
          const uploaded = await uploadMutation.mutateAsync({ files: pending.map(({ file }) => file) });
          let index = 0;
          prepared = attachments.map((attachment) => attachment.artifact
            ? attachment : { ...attachment, artifact: uploaded[index++] });
          // Keep uploaded identities on comment failure so a retry does not upload duplicates.
          setAttachments(prepared);
        } catch {
          // The upload hook displays the server error; retain the entire draft.
          finishSending();
          return;
        }
      }
      const contentMd = [trimmed, ...prepared.map(({ artifact }) => commentAttachmentMarkdown(artifact!))].filter(Boolean).join('\n\n');
      mutate(
        { workitemId, contentMd, targetAgentIds, targetHumanIds },
        {
          onSuccess: () => {
            finishSending();
            setAttachments([]);
            setDeliveryClosed(false);
            setContent('');
            setTargetAgentIds([]);
            setTargetHumanIds([]);
            setSelectedMentionNames(new Map());
            clearMentionQuery();
            setActiveMentionIndex(0);
            setIsExpanded(false);
            message.success(i18n.t('workitem:comment.sent'));
          },
          onError: (error) => {
            finishSending();
            // The whole comment rolled back, so keep the draft and explain the
            // closed delivery inline instead of leaving only a generic conflict.
            if (error instanceof ApiError && error.code === ErrorCodes.WORKITEM_DELIVERY_CLOSED) {
              setDeliveryClosed(true);
              return;
            }
            // 上一次的关闭交付提示不能残留在无关失败上，否则用户会被引导去做无效的重开交付。
            setDeliveryClosed(false);
            message.error(error instanceof ApiError ? error.message : t('comment.sendFailed'));
          },
        },
      );
    });
  };

  const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => {
    const nextValue = event.target.value;
    const caretIndex = event.target.selectionStart ?? nextValue.length;

    setContent(nextValue);
    syncSelectedMentions(nextValue);
    updateMentionQuery(nextValue, caretIndex);
  };

  const insertMention = (option: { value: string; targetType: 'AGENT' | 'HUMAN'; targetId: number }) => {
    const textarea = textAreaRef.current?.resizableTextArea?.textArea;
    const selectionStart = textarea?.selectionStart ?? content.length;
    const selectionEnd = textarea?.selectionEnd ?? selectionStart;
    const textBeforeSelection = content.slice(0, selectionStart);
    const mentionStart = textBeforeSelection.lastIndexOf('@');
    const replaceStart = mentionStart >= 0 ? mentionStart : selectionStart;
    const mentionText = `@${option.value} `;
    const nextValue = `${content.slice(0, replaceStart)}${mentionText}${content.slice(selectionEnd)}`;
    const nextCaret = replaceStart + mentionText.length;

    setContent(nextValue);
    clearMentionQuery();
    setActiveMentionIndex(0);
    if (Number.isFinite(option.targetId)) {
      setSelectedMentionNames((current) => {
        const next = new Map(current);
        next.set(`${option.targetType}:${option.targetId}`, option.value);
        return next;
      });
    }
    syncSelectedMentions(nextValue);
    if (Number.isFinite(option.targetId)) {
      if (option.targetType === 'AGENT') {
        setTargetAgentIds((current) => current.includes(option.targetId) ? current : [...current, option.targetId]);
      } else {
        setTargetHumanIds((current) => current.includes(option.targetId) ? current : [...current, option.targetId]);
      }
    }

    window.setTimeout(() => {
      textarea?.focus();
      textarea?.setSelectionRange(nextCaret, nextCaret);
    }, 0);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing) return;

    if (mentionQuery !== null && mentionOptions.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setActiveMentionIndex((current) => (current + 1) % mentionOptions.length);
        return;
      }

      if (event.key === 'ArrowUp') {
        event.preventDefault();
        setActiveMentionIndex((current) => (current - 1 + mentionOptions.length) % mentionOptions.length);
        return;
      }

      if (event.key === 'Escape') {
        event.preventDefault();
        clearMentionQuery();
        setActiveMentionIndex(0);
        return;
      }

      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        insertMention(mentionOptions[activeMentionIndex] ?? mentionOptions[0]);
        return;
      }
    }

    if (event.key !== 'Enter' || event.shiftKey) return;

    event.preventDefault();
    handleSend();
  };

  const handleKeyUp = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (['ArrowDown', 'ArrowUp', 'Enter', 'Escape'].includes(event.key)) return;

    updateMentionQuery(content, event.currentTarget.selectionStart ?? content.length);
  };

  return (
    <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 14, alignItems: 'stretch' }} data-testid="workitem-comment-input">
      {deliveryClosed && (
        <Alert
          type="warning"
          showIcon
          message={t('comment.deliveryClosedTitle')}
          description={t('comment.deliveryClosedHint')}
        />
      )}
      <div
        data-testid="comment-attachment-dropzone"
        data-drag-active={dragActive ? 'true' : 'false'}
        onPaste={handlePaste}
        onDrop={handleDrop}
        onDragEnter={(event) => {
          if (!transferContainsFiles(event.dataTransfer) || busy) return;
          event.preventDefault();
          dragDepth.current += 1;
          setDragActive(true);
        }}
        onDragOver={(event) => {
          if (!transferContainsFiles(event.dataTransfer)) return;
          event.preventDefault();
          event.dataTransfer.dropEffect = busy ? 'none' : 'copy';
        }}
        onDragLeave={(event) => {
          if (!transferContainsFiles(event.dataTransfer)) return;
          dragDepth.current = Math.max(0, dragDepth.current - 1);
          if (dragDepth.current === 0) setDragActive(false);
        }}
        style={{ borderRadius: 6, outline: dragActive ? '2px dashed var(--aw-accent)' : undefined }}
      >
        <Space.Compact style={{ width: '100%', alignItems: 'stretch' }}>
          <div style={{ flex: 1, minWidth: 0, position: 'relative' }}>
            {mentionQuery !== null && mentionOptions.length > 0 && (
              <div role="menu" style={mentionMenuStyle}>
                {mentionOptions.map((option, index) => (
                  <button
                    key={`${option.targetType}:${option.targetId}`}
                    type="button"
                    role="menuitem"
                    aria-current={index === activeMentionIndex ? 'true' : undefined}
                    style={{
                      ...mentionMenuItemStyle,
                      ...(index === activeMentionIndex ? activeMentionMenuItemStyle : {}),
                    }}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => insertMention(option)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            )}
            <Input.TextArea
              ref={textAreaRef}
              className="workitem-comment-mentions"
              value={content}
              onChange={handleChange}
              onClick={(event) => updateMentionQuery(content, event.currentTarget.selectionStart ?? content.length)}
              onFocus={() => setIsExpanded(true)}
              onBlur={() => { if (!content.trim()) setIsExpanded(false); }}
              onKeyDown={handleKeyDown}
              onKeyUp={handleKeyUp}
              autoSize={isExpanded ? { minRows: 3, maxRows: 10 } : { minRows: 1, maxRows: 10 }}
              rows={isExpanded ? 3 : 1}
              placeholder={t('comment.placeholder')}
              disabled={busy}
              style={{ width: '100%', ...composerTextStyle }}
            />
          </div>
          <Button
            icon={<SendOutlined />}
            style={{
              alignSelf: 'stretch', height: 'auto', padding: '0 18px',
              background: 'rgba(var(--aw-accent-rgb),.10)', borderColor: 'var(--aw-accent)', color: 'var(--aw-accent-text)',
            }}
            onClick={handleSend}
            loading={busy}
            disabled={!content.trim() && attachments.length === 0}
          >
            {t('comment.send')}
          </Button>
        </Space.Compact>
        {attachments.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8, maxHeight: 160, overflowY: 'auto' }}>
            {attachments.map((attachment, index) => (
              <CommentAttachment key={attachment.file.name} attachment={attachment} disabled={busy}
                onRemove={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))} />
            ))}
          </div>
        )}
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, marginTop: 8 }}>
          <input ref={fileInputRef} type="file" multiple accept={DOCUMENT_ACCEPT_ATTRIBUTE}
            aria-label={t('comment.attachFiles')} data-testid="comment-file-input" hidden disabled={busy}
            onChange={(event) => { selectFiles(Array.from(event.target.files ?? [])); event.target.value = ''; }} />
          <Button size="small" icon={<PaperClipOutlined />} disabled={busy}
            onClick={() => accessCommand('READ_WRITE', t('comment.attachFiles'), () => fileInputRef.current?.click())}>
            {t('comment.attachFiles')}
          </Button>
          <span style={{ color: 'var(--aw-muted)', fontSize: 12 }}>
            {uploadMutation.isPending ? t('comment.uploadingAttachments') : t('comment.attachmentHint')}
          </span>
        </div>
        {attachments.length > 0 && <div style={{ color: 'var(--aw-muted)', fontSize: 12, marginTop: 4 }}>{t('comment.attachmentStorageHint')}</div>}
      </div>
    </Space>
  );
}
