import { useEffect, useLayoutEffect, useMemo, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Button, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ConversationEventView } from '@/features/workitem/clarification/ConversationEventView';
import { assembleTurnEvents } from '@/features/workitem/clarification/hooks';
import { AnswerHistory } from '@/features/workitem/clarification/cards/AnswerHistory';
import { projectQuestionHistory, withoutCoveredQuestions, type QuestionHistoryEntry } from '@/features/workitem/clarification/answerHistory';
import type { ClarificationElicitation, ClarificationTurnEvent } from '@/features/workitem/clarification/types';
import { buildTimeline } from '@/features/workitem/clarification/timeline';
import { getTurnEventPage } from './api';

const LIVE_WINDOW_ROWS = 2000;
export function ChiefTurnEvents({ scope, conversationId, turnId, processing, onContentChange, questionHistory, onContinue, canContinue, onSettledQuestions }: {
  onSettledQuestions?: (conversationId: number, turnId: number, requestIds: readonly string[]) => void;
  questionHistory?: ClarificationElicitation[] | null;
  onContinue?: (entry: QuestionHistoryEntry) => void;
  canContinue?: boolean;
  onContentChange?: () => void;
  scope: readonly unknown[]; conversationId: number; turnId: number; processing: boolean;
}) {
  const { t } = useTranslation('workitem');
  const [liveRows, setLiveRows] = useState<ClarificationTurnEvent[]>([]);
  const [liveError, setLiveError] = useState(false);
  const query = useInfiniteQuery({
    queryKey: [...scope, 'turnEventPages', conversationId, turnId],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => getTurnEventPage(conversationId, turnId, pageParam),
    getNextPageParam: (page, _pages, cursor) => page.hasMore && page.nextAfterId > cursor ? page.nextAfterId : undefined,
    enabled: !processing,
    staleTime: 0,
  });
  useEffect(() => {
    setLiveRows([]); setLiveError(false);
    if (!processing) return;
    let stopped = false;
    let cursor = 0;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      let delay = 3000;
      try {
        const page = await getTurnEventPage(conversationId, turnId, cursor);
        if (stopped) return;
        setLiveError(false);
        setLiveRows(previous => [...new Map([...previous, ...page.rows].map(row => [row.id, row])).values()]
          .sort((a, b) => a.id - b.id).slice(-LIVE_WINDOW_ROWS));
        if (page.nextAfterId > cursor) {
          cursor = page.nextAfterId;
          if (page.hasMore) delay = 0;
        }
      } catch {
        if (!stopped) setLiveError(true);
      }
      if (!stopped) timer = setTimeout(() => void poll(), delay);
    };
    void poll();
    return () => { stopped = true; clearTimeout(timer); };
  }, [conversationId, turnId, processing]);
  const nodes = useMemo(() => {
    const rows = processing ? liveRows
      : [...new Map((query.data?.pages.flatMap(page => page.rows) ?? []).map(row => [row.id, row])).values()];
    const events = assembleTurnEvents(rows);
    const attempt = events.reduce((max, event) => Math.max(max, event.dispatchAttempt ?? 0), 0);
    return buildTimeline(events).filter(node => node.kind !== 'thinking'
      && (node.kind !== 'text' || (processing && (node.dispatchAttempt ?? 0) === attempt)));
  }, [query.data, liveRows, processing]);
  const history = useMemo(() => projectQuestionHistory(turnId, questionHistory, nodes), [turnId, questionHistory, nodes]);
  const settledRequestIds = useMemo(() => nodes.filter(node => node.elicitation?.resolved)
    .map(node => node.elicitation!.requestId), [nodes]);
  useEffect(() => { onSettledQuestions?.(conversationId, turnId, settledRequestIds); },
    [conversationId, turnId, settledRequestIds, onSettledQuestions]);
  useLayoutEffect(() => { onContentChange?.(); }, [nodes, history, onContentChange]);
  return <>
    <AnswerHistory entries={history} onContinue={onContinue} canContinue={canContinue} />
    <ConversationEventView nodes={withoutCoveredQuestions(nodes, history)} isProcessing={false} />
    {(processing ? liveError : query.isError) && <Typography.Text type="danger">{t('clarification.detailsFailed')}</Typography.Text>}
    {!processing && query.hasNextPage && <Button size="small" loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{t('clarification.loadMoreDetails')}</Button>}
  </>;
}
