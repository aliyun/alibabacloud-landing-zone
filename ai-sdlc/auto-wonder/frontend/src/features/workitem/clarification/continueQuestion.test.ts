import { describe, it, expect } from 'vitest';
import { appendQuestionContext, CONTINUE_CONTEXT_LIMIT } from './continueQuestion';
import type { QuestionHistoryEntry } from './answerHistory';
const entry: QuestionHistoryEntry = { turnId: 1, requestId: 'expired', resolved: true, status: 'EXPIRED', message: 'Question subject', requestedSchema: { type: 'object', properties: { answer: { type: 'string', title: 'Readable field', description: 'Actual question', enum: ['A', 'B'] } }, sourceContext: 'PRIVATE-SOURCE-CONTEXT', attachmentUrl: 'PRIVATE-ATTACHMENT' } };
describe('explicit expired-question draft continuation', () => {
  it('bounds only inserted context, reserving space for the original IN and question; preserves long draft exactly and repeated click', () => {
    const draft = 'My authored draft\n' + 'd'.repeat(20000);
    const restored = appendQuestionContext(draft, 'Original request ' + 'x'.repeat(20000), { ...entry, message: 'Question subject ' + 'y'.repeat(20000) });
    expect(restored.startsWith(draft + '\n\n')).toBe(true);
    expect(restored.length - draft.length - 2).toBeLessThanOrEqual(CONTINUE_CONTEXT_LIMIT);
    expect(restored).toContain('Original request'); expect(restored).toContain('Question subject');
    expect(restored).toContain('上下文过长，已节选');
    expect(appendQuestionContext(restored, 'Original request ' + 'x'.repeat(20000), { ...entry, message: 'Question subject ' + 'y'.repeat(20000) })).toBe(restored);
  });
  it('copies readable schema fields but excludes source context, credential values and generated attachment links', () => {
    const restored = appendQuestionContext('', 'Original request {"apiKey":"PRIVATE-KEY"}\nBearer PRIVATE-TOKEN\nhttps://site/api/attachments/secret-file', entry);
    expect(restored).toContain('Readable field'); expect(restored).toContain('Actual question');
    for (const secret of ['PRIVATE-KEY', 'PRIVATE-TOKEN', 'PRIVATE-SOURCE-CONTEXT', 'PRIVATE-ATTACHMENT', 'secret-file']) expect(restored).not.toContain(secret);
  });
});
