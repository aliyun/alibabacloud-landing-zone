import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ChiefTurnEvents } from './ChiefTurnEvents';
import * as api from './api';
vi.mock('./api', () => ({ getTurnEventPage: vi.fn() }));
vi.mock('@/features/workitem/clarification/ConversationEventView', () => ({ ConversationEventView: ({ nodes }: { nodes: { text?: string }[] }) => <div>{nodes.map(n => n.text).join('')}</div> }));

describe('active conversation cursor', () => {
  it('automatically follows later pages without requiring a history button', async () => {
    vi.mocked(api.getTurnEventPage).mockResolvedValueOnce({ rows: [], nextAfterId: 500, hasMore: true })
      .mockResolvedValue({ rows: [{ id: 501, conversationId: 1, turnId: 2, dispatchAttempt: 2, eventSeq: 1,
        chunkIndex: 0, chunkCount: 1, eventType: 'text', payloadFragment: '{"content":"latest reply"}', gmtCreate: '' }], nextAfterId: 501, hasMore: false });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const onContentChange = vi.fn();
    const rendered = render(<QueryClientProvider client={client}><ChiefTurnEvents scope={['test']} conversationId={1} turnId={2} processing onContentChange={onContentChange} /></QueryClientProvider>);
    await waitFor(() => expect(api.getTurnEventPage).toHaveBeenCalledWith(1, 2, 500));
    expect(await screen.findByText('latest reply')).toBeInTheDocument();
    expect(onContentChange).toHaveBeenCalled();
    rendered.unmount(); client.clear();
  });
});


it('merges seven snapshot answers and paged legacy questions once without losing uncertainty', async () => {
  const cards = Array.from({ length: 7 }, (_, i) => ({ requestId: `q${i}`, turnId: 2, mode: 'form',
    message: `Question ${i}`, requestedSchema: '{"type":"object","properties":{"value":{"type":"string","title":"Choice"}}}',
    status: 'ANSWERED', gmtCreate: '', answerJson: JSON.stringify({ value: `answer ${i}` }),
    deliveryStatus: i === 2 ? 'UNKNOWN' as const : 'WRITTEN' as const }));
  const row = (id: number, requestId: string, eventType: string, extra = {}) => ({ id, conversationId: 1, turnId: 2, dispatchAttempt: 0, eventSeq: id,
    chunkIndex: 0, chunkCount: 1, eventType, payloadFragment: JSON.stringify({ data: { requestId, ...extra } }), gmtCreate: '' });
  vi.mocked(api.getTurnEventPage).mockResolvedValueOnce({ rows: [row(1, 'q0', 'acp_elicitation', { message: 'Question 0' })], nextAfterId: 1, hasMore: true })
    .mockResolvedValue({ rows: [row(2, 'q0', 'acp_elicitation_resolved', { action: 'accept', content: { value: 'stale answer' } }),
      row(3, 'legacy', 'acp_elicitation', { message: 'Legacy question' }), row(4, 'legacy', 'acp_elicitation_resolved', { action: 'accept', content: { value: 'legacy answer' } })], nextAfterId: 4, hasMore: false });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const rendered = render(<QueryClientProvider client={client}><ChiefTurnEvents scope={['history']} conversationId={1} turnId={2} processing={false} questionHistory={cards} /></QueryClientProvider>);
  for (let i = 0; i < 7; i++) expect(await screen.findAllByText(`answer ${i}`)).toHaveLength(1);
  expect(screen.getByTestId('question-history-2-q2')).toHaveTextContent('回答的处理状态无法确认');
  fireEvent.click(await screen.findByRole('button', { name: '加载更多执行详情' }));
  expect(await screen.findByText('legacy answer')).toBeInTheDocument();
  expect(screen.getAllByText('answer 0')).toHaveLength(1);
  expect(screen.queryByText('stale answer')).not.toBeInTheDocument();
  rendered.unmount(); client.clear();
});
