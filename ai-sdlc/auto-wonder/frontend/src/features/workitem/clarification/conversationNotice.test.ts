import { describe, it, expect } from 'vitest';
import { i18n } from '@/shared/i18n';
import { conversationNotice } from './conversationNotice';

describe('expired turn display intent', () => {
  it('maps only finite QUESTION_EXPIRED metadata with empty args in both display positions and languages', async () => {
    for (const language of ['zh-CN', 'en-US']) {
      await i18n.changeLanguage(language);
      const t = i18n.getFixedT(language, 'workitem');
      for (const field of ['content', 'error'] as const) {
        expect(conversationNotice({ code: 'QUESTION_EXPIRED', args: {} }, field, t)).toBe(t('clarification.ownedNotice.questionExpired'));
        expect(conversationNotice({ code: 'QUESTION_EXPIRED', args: { detail: 'untrusted' } }, field, t)).toBeNull();
      }
    }
    await i18n.changeLanguage('zh-CN');
  });
});
