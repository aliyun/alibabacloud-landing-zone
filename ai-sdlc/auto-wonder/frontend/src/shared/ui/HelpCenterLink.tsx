import { useTranslation } from 'react-i18next';
import { Button } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';

export function HelpCenterLink() {
  const { t } = useTranslation('shared');
  return (
    <Button type="text" href="/help" target="_blank" rel="noopener noreferrer" aria-label={t('help')} icon={<QuestionCircleOutlined />}>{t('help')}</Button>
  );
}
