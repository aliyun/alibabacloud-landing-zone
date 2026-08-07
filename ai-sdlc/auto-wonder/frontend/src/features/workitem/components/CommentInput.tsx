import { useEffect, useMemo, useRef, useState, type CSSProperties, type ChangeEvent, type KeyboardEvent } from 'react';
import { Button, Input, Space, message } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { SendOutlined } from '@ant-design/icons';
import { useAddComment } from '../hooks';
import type { Participant } from '@/shared/types/workitem';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

interface CommentInputProps {
  workitemId: string;
  participants?: Participant[];
  mentionCandidates?: Participant[];
  onMentionQueryChange?: (query: string | null) => void;
}

const mentionOptionHighlightStyle: CSSProperties = {
  color: '#0958d9',
  background: '#e6f4ff',
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
  background: '#fff',
  border: '1px solid #d9d9d9',
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
  textAlign: 'left',
  cursor: 'pointer',
};

const activeMentionMenuItemStyle: CSSProperties = {
  background: '#f0f7ff',
};

function findActiveMentionQuery(value: string, caretIndex: number): string | null {
  const textBeforeCaret = value.slice(0, caretIndex);
  const mentionStart = textBeforeCaret.lastIndexOf('@');

  if (mentionStart < 0) return null;

  const query = textBeforeCaret.slice(mentionStart + 1);
  if (/\s/.test(query)) return null;

  return query;
}

export function CommentInput({ workitemId, participants = [], mentionCandidates, onMentionQueryChange }: CommentInputProps) {
  const [content, setContent] = useState('');
  const [targetAgentIds, setTargetAgentIds] = useState<number[]>([]);
  const [targetHumanIds, setTargetHumanIds] = useState<number[]>([]);
  const [selectedMentionNames, setSelectedMentionNames] = useState<Map<string, string>>(new Map());
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [activeMentionIndex, setActiveMentionIndex] = useState(0);
  const [isExpanded, setIsExpanded] = useState(false);
  const textAreaRef = useRef<TextAreaRef>(null);
  const { mutate, isPending } = useAddComment();
  const accessCommand = useAccessCommand();

  const allMentionCandidates = useMemo(
    () => mentionCandidates ?? participants,
    [mentionCandidates, participants],
  );

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
      .filter((item) => item.name.toLowerCase().includes(normalizedQuery)
        || String(item.displayId ?? item.userId).toLowerCase().includes(normalizedQuery))
      .map((item) => {
        const targetType: 'AGENT' | 'HUMAN' = item.isAgent ? 'AGENT' : 'HUMAN';
        const targetId = Number(item.userId);
        const kindLabel = item.isAgent ? '数字人' : '真人';
        return {
          value: item.name,
          label: (
            <span>
              <span style={mentionOptionHighlightStyle}>
                @{item.name}
              </span>
              <span style={{ marginLeft: 6, color: item.isAgent ? '#fa8c16' : '#1677ff' }}>{kindLabel}</span>
              {item.displayId ? <span style={{ marginLeft: 6, color: '#8c8c8c' }}>{item.displayId}</span> : null}
              {item.isAgent && !item.online ? '（离线，稍后送达）' : ''}
            </span>
          ),
          targetId,
          targetType,
        };
      });
  }, [allMentionCandidates, mentionQuery]);

  const updateMentionQuery = (value: string, caretIndex: number) => {
    const nextQuery = findActiveMentionQuery(value, caretIndex);
    setMentionQuery(nextQuery);
    onMentionQueryChange?.(nextQuery);
  };

  const clearMentionQuery = () => {
    setMentionQuery(null);
    onMentionQueryChange?.(null);
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
    setTargetAgentIds((current) => current.filter((agentId) => hasMentionText(value, 'AGENT', agentId)));
    setTargetHumanIds((current) => current.filter((humanId) => hasMentionText(value, 'HUMAN', humanId)));
  };

  const handleSend = () => {
    const trimmed = content.trim();
    if (!trimmed) return;

    accessCommand('READ_WRITE', '发表评论', () => {
      mutate(
        { workitemId, contentMd: trimmed, targetAgentIds, targetHumanIds },
        {
          onSuccess: () => {
            setContent('');
            setTargetAgentIds([]);
            setTargetHumanIds([]);
            setSelectedMentionNames(new Map());
            clearMentionQuery();
            setActiveMentionIndex(0);
            setIsExpanded(false);
            message.success('评论已发送');
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
            placeholder="输入评论，键入 @ 选择成员..."
            disabled={isPending}
            style={{ width: '100%', ...composerTextStyle }}
          />
        </div>
        <Button
          icon={<SendOutlined />}
          style={{
            alignSelf: 'stretch', height: 'auto', padding: '0 18px',
            background: '#fff7ed', borderColor: '#fdba74', color: '#c2410c',
          }}
          onClick={handleSend}
          loading={isPending}
        >
          发送
        </Button>
      </Space.Compact>
    </Space>
  );
}
