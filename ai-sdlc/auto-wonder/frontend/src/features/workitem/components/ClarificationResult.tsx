import { Typography } from 'antd';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import type { Clarification } from '@/shared/types/workitem';

const { Text } = Typography;

interface ClarificationResultProps {
  clarification: Clarification | null | undefined;
}

export function ClarificationResult({ clarification }: ClarificationResultProps) {
  if (!clarification || !clarification.contentMd) {
    return null;
  }

  return (
    <div
      style={{
        background: '#fff7ed',
        border: '1px solid #fed7aa',
        borderRadius: 8,
        padding: 16,
      }}
    >
      <Text strong style={{ display: 'block', marginBottom: 12, fontSize: 14, color: '#c2410c' }}>
        澄清材料 (AI 生成)
      </Text>
      <MarkdownView content={clarification.contentMd} />
    </div>
  );
}
