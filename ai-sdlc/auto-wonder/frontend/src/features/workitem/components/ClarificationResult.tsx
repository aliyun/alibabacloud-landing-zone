import { useTranslation } from 'react-i18next';
import { Typography } from 'antd';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { Clarification } from '@/shared/types/workitem';

const { Text } = Typography;

interface ClarificationResultProps {
  clarification: Clarification | null | undefined;
}

export function ClarificationResult({ clarification }: ClarificationResultProps) {
  const { t } = useTranslation('workitem');
  if (!clarification || !clarification.contentMd) {
    return null;
  }

  return (
    <div
      style={{
        background: 'rgba(var(--aw-accent-rgb),.10)',
        border: '1px solid var(--aw-accent)',
        borderRadius: 8,
        padding: 16,
      }}
    >
      <Text strong style={{ display: 'block', marginBottom: 12, fontSize: 14, color: 'var(--aw-accent-text)' }}>
        {t('detail.clarificationResult')}
      </Text>
      <MarkdownView content={clarification.contentMd} />
    </div>
  );
}
