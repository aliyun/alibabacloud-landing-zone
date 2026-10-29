import { expect, it } from 'vitest';
import { clarificationConversationRefetchInterval } from './hooks';
it('keeps reconciling answer delivery after the turn detail becomes terminal', () => {
  expect(clarificationConversationRefetchInterval({ processingStatus: null, answerDeliveries: [{ deliveryStatus: 'SAVED' }] })).toBe(3000);
  expect(clarificationConversationRefetchInterval({ processingStatus: null, answerDeliveries: [{ deliveryStatus: 'WRITTEN' }] })).toBe(false);
});
