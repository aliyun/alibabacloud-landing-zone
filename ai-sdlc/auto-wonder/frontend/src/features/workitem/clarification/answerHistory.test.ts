import { describe, it, expect } from 'vitest';
import { projectQuestionHistory, withoutCoveredQuestions } from './answerHistory';
import { buildTimeline } from './timeline';
import type { ClarificationElicitation } from './types';
const row: ClarificationElicitation = { requestId: 'same', turnId: 1, mode: 'form', message: 'Same question', requestedSchema: null,
  status: 'ANSWERED', gmtCreate: '', answerJson: '{"answer":" exact \\nanswer "}', deliveryStatus: 'WRITTEN' };
describe('per-IN question projection', () => {
  it('merges resolved-before-opening and duplicate events with authoritative snapshots by identity, never text', () => {
    const nodes = buildTimeline([
      { turnId: 1, eventSeq: 1, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'same', action: 'accept', content: { answer: 'stale' } } } },
      { turnId: 1, eventSeq: 2, eventType: 'acp_elicitation', payload: { type: '', data: { requestId: 'same', message: 'Same question' } } },
      { turnId: 1, eventSeq: 3, eventType: 'acp_elicitation', payload: { type: '', data: { requestId: 'pending' } } },
    ]);
    const entries = projectQuestionHistory(1, [row, { ...row, requestId: 'other' }, { ...row, turnId: 2 }], nodes);
    expect(entries).toHaveLength(2);
    expect(entries.find(entry => entry.requestId === 'same')?.content).toEqual({ answer: ' exact \nanswer ' });
    expect(withoutCoveredQuestions(nodes, entries).map(node => node.elicitation?.requestId)).toEqual(['pending']);
    expect(projectQuestionHistory(2, [row, { ...row, turnId: 2 }])).toHaveLength(1);
  });
  it('retains legacy event-only history and authoritative live expiry even without a saved snapshot', () => {
    const nodes = buildTimeline([
      { turnId: 1, eventSeq: 1, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'legacy', action: 'cancel', status: 'EXPIRED' } } },
      { turnId: 1, eventSeq: 2, eventType: 'acp_elicitation', payload: { type: '', data: { requestId: 'legacy', message: 'Late question' } } },
    ]);
    expect(projectQuestionHistory(1, undefined, nodes)[0]).toMatchObject({ message: 'Late question', status: 'EXPIRED', resolved: true });
  });
  it('does not display a stale live answer after durable expiry won', () => {
    const nodes = buildTimeline([{ turnId: 1, eventSeq: 1, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'same', action: 'accept', content: { answer: 'stale' } } } }]);
    expect(projectQuestionHistory(1, [{ ...row, status: 'EXPIRED', answerJson: null, deliveryStatus: null }], nodes)[0].content).toBeUndefined();
  });
  it('preserves malformed answers in expandable history and malformed schema without crashing', () => {
    expect(projectQuestionHistory(1, [{ ...row, requestedSchema: '{bad schema', answerJson: '{bad answer' }])[0]).toMatchObject({ rawAnswer: '{bad answer', requestedSchema: '{bad schema' });
  });
});

it('keeps older saved questions before a new live answer through snapshot convergence in a partial stream', () => {
  const saved1 = { ...row, requestId: 'older-1', gmtCreate: '2026-10-27T10:00:00' };
  const saved2 = { ...row, requestId: 'older-2', gmtCreate: '2026-10-27T10:01:00' };
  const saved3 = { ...row, requestId: 'new-live', gmtCreate: '2026-10-27T10:02:00' };
  const nodes = buildTimeline([{ turnId: 1, eventSeq: 30, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'new-live', action: 'accept', content: { answer: 'new live answer' } } } }]);
  const ids = (saved: ClarificationElicitation[], events = nodes) => projectQuestionHistory(1, saved, events).map(entry => entry.requestId);
  expect(ids([saved2, saved1], [])).toEqual(['older-1', 'older-2']);
  expect(ids([saved2, saved1])).toEqual(['older-1', 'older-2', 'new-live']);
  expect(ids([saved3, saved2, saved1])).toEqual(['older-1', 'older-2', 'new-live']);
  expect(ids([saved3, saved2, saved1], [])).toEqual(['older-1', 'older-2', 'new-live']);
});

it('retains event-only history before known durable anchors and a new live tail after durable-only questions', () => {
  const nodes = buildTimeline([
    { turnId: 1, eventSeq: 10, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'legacy', action: 'accept', content: { answer: 'legacy' } } } },
    { turnId: 1, eventSeq: 20, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'older-1', action: 'accept', content: { answer: 'stale' } } } },
    { turnId: 1, eventSeq: 30, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'new-live', action: 'accept', content: { answer: 'live' } } } },
  ]);
  const saved = [{ ...row, requestId: 'older-2', gmtCreate: '2026-10-27T10:01:00' }, { ...row, requestId: 'older-1', gmtCreate: '2026-10-27T10:00:00' }];
  expect(projectQuestionHistory(1, saved, nodes).map(entry => entry.requestId)).toEqual(['legacy', 'older-1', 'older-2', 'new-live']);
});

it('keeps server newest-first snapshot ties stable when a live tail becomes durable', () => {
  const saved1 = { ...row, requestId: 'z-old', gmtCreate: '2026-10-27T10:00:00' };
  const saved2 = { ...row, requestId: 'y-old', gmtCreate: '2026-10-27T10:00:00' };
  const saved3 = { ...row, requestId: 'a-new', gmtCreate: '2026-10-27T10:00:00' };
  const nodes = buildTimeline([{ turnId: 1, eventSeq: 30, eventType: 'acp_elicitation_resolved', payload: { type: '', data: { requestId: 'a-new', action: 'accept' } } }]);
  expect(projectQuestionHistory(1, [saved2, saved1], nodes).map(entry => entry.requestId)).toEqual(['z-old', 'y-old', 'a-new']);
  expect(projectQuestionHistory(1, [saved3, saved2, saved1], nodes).map(entry => entry.requestId)).toEqual(['z-old', 'y-old', 'a-new']);
});
