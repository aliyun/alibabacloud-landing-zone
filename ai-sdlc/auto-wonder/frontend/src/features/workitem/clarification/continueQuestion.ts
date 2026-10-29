import { i18n } from '@/shared/i18n';
import type { QuestionHistoryEntry } from './answerHistory';
import { parseElicitationSchema } from './cards/schemaForm';

/** Only the generated block is bounded; an existing authored draft is never shortened. */
export const CONTINUE_CONTEXT_LIMIT = 12000;
export function appendQuestionContext(draft: string, originalInput: string, entry: QuestionHistoryEntry): string {
  const t = i18n.getFixedT(null, 'workitem');
  const form = parseElicitationSchema(entry.requestedSchema);
  const question = [entry.message, form.title, ...form.fields.flatMap(field => [field.label, field.description,
    ...(field.options ?? []).map(option => `${option.label} (${option.value})`)])].filter(Boolean).join('\n');
  const clean = (text: string) => text
    .replace(/\b(?:Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, t('clarification.history.redacted'))
    .replace(/\b(password|passwd|secret|api[_-]?key|access[_-]?key(?:[_-]?id|[_-]?secret)?|token|authorization)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1: ' + t('clarification.history.redacted'))
    .replace(/https?:\/\/[^\s)"<>]*(?:\/attachments?\/|\/images?\/)[^\s)"<>]*/gi, t('clarification.history.redacted'));
  const bound = (text: string, limit: number) => text.length <= limit ? text
    : text.slice(0, limit - t('clarification.history.truncated').length - 1) + '\n' + t('clarification.history.truncated');
  // Reserve half of the remaining budget for each source, including localized headings.
  const intro = t('clarification.history.continueIntro');
  const originalLabel = t('clarification.history.originalRequest');
  const questionLabel = t('clarification.history.questionContext');
  const sourceBudget = Math.floor((CONTINUE_CONTEXT_LIMIT - intro.length - originalLabel.length - questionLabel.length - 8) / 2);
  const block = [intro, originalLabel, bound(clean(originalInput), sourceBudget),
    questionLabel, bound(clean(question), sourceBudget)].join('\n\n');
  if (draft.includes(block)) return draft;
  return draft ? `${draft}\n\n${block}` : block;
}
