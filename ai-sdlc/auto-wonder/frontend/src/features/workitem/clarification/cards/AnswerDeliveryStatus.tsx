import { useTranslation } from 'react-i18next';
import type { ClarificationElicitation } from '../types';

/** One current receipt fact; question history owns answers and uncertain outcomes. */
export function AnswerDeliveryStatus({ cards, currentTurnId }: {
  cards?: ClarificationElicitation[] | null; currentTurnId?: number | null;
}) {
  const { t } = useTranslation('workitem');
  const card = cards?.filter(row => row.turnId === currentTurnId
    && (row.deliveryStatus === 'SAVED' || row.deliveryStatus === 'RECEIVED'))
    .sort((a, b) => b.gmtCreate.localeCompare(a.gmtCreate) || b.requestId.localeCompare(a.requestId))[0];
  if (!card || (card.deliveryStatus !== 'SAVED' && card.deliveryStatus !== 'RECEIVED')) return null;
  return <div aria-live="polite" data-testid={`answer-delivery-${card.requestId}`} style={{ padding: '4px 12px' }}>
    {t(`clarification.answerDelivery.${card.deliveryStatus}`)}
  </div>;
}
