import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Typography, Empty } from 'antd';
import { SendOutlined, PlusOutlined, UserOutlined, RobotOutlined } from '@ant-design/icons';
import { AgentSelector } from './AgentSelector';
import { SquadAgentSelector } from './SquadAgentSelector';
import type { SquadAgentSelection } from './SquadAgentSelector';
import { ConversationEventView } from './ConversationEventView';
import {
  useClarificationConversations,
  useClarificationConversation,
  useCreateClarificationConversation,
  useSubmitClarificationTurn,
  useClarificationEvents,
} from './hooks';
import {
  readClarificationPrefill,
  writeClarificationPrefill,
} from './prefill';
import type { ClarificationTurn } from './types';
import type { AgentDeliveryProgress } from '@/shared/types/workitem';

const clarificationBootstrapPrompt = (workitemId: string) =>
  `请通过 AutoWonder MCP 读取工单 #${workitemId}，与我进行需求澄清。`;

interface WorkitemClarificationPanelProps {
  workitemId: string;
  /** Agents already bound to an active delivery. When empty, the panel
   *  falls back to a squad -> member selector so the user can start a
   *  clarification conversation before starting delivery. */
  agents: AgentDeliveryProgress[];
}

export function WorkitemClarificationPanel({
  workitemId,
  agents,
}: WorkitemClarificationPanelProps) {
  const hasDeliveryAgents = agents.length > 0;

  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);

  const [selection, setSelection] = useState<SquadAgentSelection | null>(() => {
    if (hasDeliveryAgents) return null;
    return readClarificationPrefill(workitemId);
  });

  const [conversationId, setConversationId] = useState<number | null>(null);
  const [inputValue, setInputValue] = useState('');
  const composingRef = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const agentOptions = useMemo(
    () =>
      agents.map((a) => ({
        agentId: a.agentId,
        agentName: a.agentName,
        executorOnline: a.status !== 'failed' && a.status !== 'pending',
      })),
    [agents],
  );

  const effectiveAgentId = hasDeliveryAgents
    ? selectedAgentId
    : selection?.agentId && selection.agentId > 0
      ? selection.agentId
      : null;

  const conversations = useClarificationConversations(workitemId, effectiveAgentId);
  const conversation = useClarificationConversation(workitemId, conversationId);
  const createMutation = useCreateClarificationConversation(workitemId);
  const submitMutation = useSubmitClarificationTurn(workitemId, conversationId);
  const { streamedEvents, streamedText, streamedTurnCompleted } = useClarificationEvents(
    workitemId,
    conversationId,
    conversation.data?.processingTurnId,
  );

  const convData = conversation.data;
  const isProcessing = convData?.processingStatus === 'PROCESSING';
  const turns = convData?.turns ?? [];

  const turnCountAtSubmitRef = useRef(turns.length);
  const prevIsProcessingRef = useRef(false);

  useEffect(() => {
    if (submitMutation.isPending) {
      turnCountAtSubmitRef.current = turns.length;
    }
  }, [submitMutation.isPending, turns.length]);

  useEffect(() => {
    if (prevIsProcessingRef.current && !isProcessing) {
      // processing just ended — turn count will be checked in render
    }
    prevIsProcessingRef.current = isProcessing;
  }, [isProcessing]);

  const hasPersistedStreamedReply = turns.some(
    (turn) => turn.direction !== 'INBOUND' && turn.content === streamedText,
  );
  const awaitingAgentReply = !!streamedText
    && !hasPersistedStreamedReply
    && (streamedTurnCompleted || (!isProcessing && turns.length === turnCountAtSubmitRef.current));
  const latestTurn = turns[turns.length - 1];
  const latestTurnIsAgentReply = !!latestTurn
    && latestTurn.direction !== 'IN'
    && latestTurn.direction !== 'INBOUND';
  const showProcessingEvents = isProcessing && !streamedTurnCompleted && !latestTurnIsAgentReply;

  const handleSelectAgent = useCallback(
    (agentId: number) => {
      setSelectedAgentId(agentId);
      setConversationId(null);
    },
    [],
  );

  const handleSelectionChange = useCallback(
    (next: SquadAgentSelection | null) => {
      setSelection(next);
      setConversationId(null);
      if (next && next.agentId > 0) {
        writeClarificationPrefill(workitemId, {
          squadId: next.squadId,
          agentId: next.agentId,
        });
      }
    },
    [workitemId],
  );

  useEffect(() => {
    if (conversations.data && conversations.data.length > 0 && conversationId === null) {
      setConversationId(conversations.data[0].id);
    }
  }, [conversations.data, conversationId]);

  const handleCreateConversation = useCallback(() => {
    const agentId = effectiveAgentId;
    if (!agentId) return;
    createMutation.mutate(agentId, {
      onSuccess: (conv) => {
        setConversationId(conv.id);
        if (!inputValue.trim()) {
          setInputValue(
            clarificationBootstrapPrompt(workitemId)
          );
        }
      },
    });
  }, [effectiveAgentId, createMutation, workitemId, inputValue]);

  useEffect(() => {
    if (conversationId && turns.length === 0 && !inputValue.trim() && !isProcessing) {
      setInputValue(
        clarificationBootstrapPrompt(workitemId)
      );
    }
  }, [conversationId, turns.length, isProcessing, workitemId]);

  const handleSend = useCallback(() => {
    const content = inputValue.trim();
    if (!content || !conversationId || submitMutation.isPending) return;
    setInputValue('');
    submitMutation.mutate(content);
  }, [inputValue, conversationId, submitMutation]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el || typeof el.scrollTo !== 'function') return;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [turns, streamedEvents]);

  const needsAgentSelection = hasDeliveryAgents
    ? !selectedAgentId
    : !effectiveAgentId;

  if (needsAgentSelection) {
    return (
      <div style={{ padding: 12 }}>
        {hasDeliveryAgents ? (
          <>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              选择数字人
            </Typography.Text>
            <AgentSelector
              agents={agentOptions}
              selectedAgentId={selectedAgentId}
              onSelect={handleSelectAgent}
            />
          </>
        ) : (
          <>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              选择小队和数字人
            </Typography.Text>
            <SquadAgentSelector
              value={selection}
              onChange={handleSelectionChange}
            />
            <Typography.Paragraph
              type="secondary"
              style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}
            >
              无需先启动交付——澄清完成后，可在「启动交付」时复用这里的选定小队与数字人。
            </Typography.Paragraph>
          </>
        )}
      </div>
    );
  }

  const displayName = convData?.agentName
    ?? (hasDeliveryAgents
      ? agents.find((a) => a.agentId === selectedAgentId)?.agentName
      : undefined)
    ?? '数字人';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Button
          size="small"
          onClick={() => {
            if (hasDeliveryAgents) {
              setSelectedAgentId(null);
            } else {
              setSelection(null);
            }
          }}
        >
          切换数字人
        </Button>
        <Typography.Text strong style={{ flex: 1 }}>
          {displayName}
        </Typography.Text>
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={handleCreateConversation}
          loading={createMutation.isPending}
        >
          新对话
        </Button>
      </div>

      <div ref={scrollRef} style={{ flex: 1, overflow: 'auto', padding: '8px 12px' }}>
        {!conversationId ? (
          <Empty description="暂无对话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <>
            {turns.map((turn) => (
              <TurnBubble key={turn.id} turn={turn} agentName={displayName} />
            ))}
            {showProcessingEvents && (
              <ConversationEventView events={streamedEvents} isProcessing />
            )}
            {awaitingAgentReply && (
              <TurnBubble
                turn={{ id: -1, direction: 'OUTBOUND' as const, content: streamedText, status: '', error: null, gmtCreate: '' }}
                agentName={displayName}
              />
            )}
          </>
        )}
      </div>

      {conversationId ? (
        <div style={{ padding: '8px 12px', borderTop: '1px solid #f0f0f0' }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <Input.TextArea
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.nativeEvent.isComposing || composingRef.current) return;
                if (e.shiftKey) return;
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSend();
                }
              }}
              onCompositionStart={() => { composingRef.current = true; }}
              onCompositionEnd={() => { composingRef.current = false; }}
              placeholder="输入消息..."
              autoSize={{ minRows: 1, maxRows: 4 }}
              disabled={submitMutation.isPending}
              style={{ flex: 1 }}
            />
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              loading={submitMutation.isPending}
              disabled={!inputValue.trim()}
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}

function TurnBubble({ turn, agentName }: { turn: ClarificationTurn; agentName?: string }) {
  const isUser = turn.direction === 'IN';
  const label = isUser ? '你' : (agentName || 'AI');
  const icon = isUser
    ? <UserOutlined style={{ fontSize: 12, marginRight: 4 }} />
    : <RobotOutlined style={{ fontSize: 12, marginRight: 4 }} />;

  return (
    <div
      style={{
        marginBottom: 12,
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
      }}
    >
      <div style={{ maxWidth: '85%' }}>
        <div
          style={{
            fontSize: 11,
            color: '#8c8c8c',
            marginBottom: 2,
            display: 'flex',
            alignItems: 'center',
            justifyContent: isUser ? 'flex-end' : 'flex-start',
          }}
        >
          {icon}
          {label}
        </div>
        <div
          style={{
            padding: '8px 12px',
            borderRadius: 8,
            backgroundColor: isUser ? '#e6f7ff' : '#f5f5f5',
            border: isUser ? '1px solid #91d5ff' : '1px solid #d9d9d9',
            whiteSpace: 'pre-wrap',
            fontSize: 13,
            lineHeight: '1.6',
          }}
        >
          {turn.content}
          {turn.error ? (
            <Typography.Text type="danger" style={{ display: 'block', fontSize: 11, marginTop: 4 }}>
              {turn.error}
            </Typography.Text>
          ) : null}
        </div>
      </div>
    </div>
  );
}
