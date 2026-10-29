import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { AnswerDeliveryStatus } from './AnswerDeliveryStatus';
import { AnswerHistory } from './AnswerHistory';
import { projectQuestionHistory } from '../answerHistory';
import type { ClarificationElicitation } from '../types';
const card: ClarificationElicitation = { requestId: 'q', turnId: 1, mode: 'form', message: 'Choose', requestedSchema: null,
  status: 'ANSWERED', gmtCreate: '', answerJson: '{"choice":"saved choice"}', deliveryStatus: 'SAVED' };
describe('AnswerDeliveryStatus', () => {
  it('preserves the answer in history and distinguishes saved from provider write', () => {
    const view = (row: ClarificationElicitation) => <><AnswerHistory entries={projectQuestionHistory(1, [row])} /><AnswerDeliveryStatus cards={[row]} currentTurnId={1} /></>;
    const { rerender } = render(view(card));
    expect(screen.getByText('回答已保存，等待送达执行器')).toBeInTheDocument();
    expect(screen.getByText(/saved choice/)).toBeInTheDocument();
    rerender(view({ ...card, deliveryStatus: 'WRITTEN' }));
    expect(screen.queryByText('回答已写入执行器，等待后续进展')).not.toBeInTheDocument();
    expect(screen.getByText(/saved choice/)).toBeInTheDocument();
    expect(screen.queryByText('正在继续分析')).not.toBeInTheDocument();
  });
  it('shows an uncertain outcome beside its question without offering to repeat the answer', () => {
    render(<><AnswerHistory entries={projectQuestionHistory(1, [{ ...card, deliveryStatus: 'UNKNOWN' }])} /><AnswerDeliveryStatus cards={[{ ...card, deliveryStatus: 'UNKNOWN' }]} currentTurnId={1} /></>);
    expect(screen.getByText('回答的处理状态无法确认，已保留答案，请查看本轮结果')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByTestId('question-history-1-q')).toHaveTextContent('saved choice');
  });
  it('keeps at most one current receipt and excludes historical receipts', () => {
    render(<AnswerDeliveryStatus currentTurnId={2} cards={[card, { ...card, turnId: 2, requestId: 'b', deliveryStatus: 'RECEIVED' }, { ...card, turnId: 2, requestId: 'c' }]} />);
    expect(screen.getAllByTestId(/^answer-delivery-/)).toHaveLength(1);
    expect(screen.queryByTestId('answer-delivery-q')).not.toBeInTheDocument();
  });
});
