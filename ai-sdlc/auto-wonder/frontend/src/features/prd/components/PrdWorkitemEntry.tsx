import { useTranslation } from 'react-i18next';
import { MessageOutlined } from '@ant-design/icons';
import './PrdWorkitemEntry.css';

interface PrdWorkitemEntryProps {
  onClarify?: () => void;
}

/** Community workitems expose clarification only; PRD design is not offered. */
export function PrdWorkitemEntry({ onClarify }: PrdWorkitemEntryProps) {
  const { t } = useTranslation('prd');
  if (!onClarify) return null;
  return (
    <nav className="workitem-design-entry" aria-label={t('entry.tools')}>
      {onClarify && <button type="button" className="workitem-design-entry__item" onClick={onClarify}>
        <MessageOutlined aria-hidden="true" />
        <span><strong>{t('entry.clarification')}</strong><small>{t('entry.clarificationHint')}</small></span>
      </button>}
    </nav>
  );
}
