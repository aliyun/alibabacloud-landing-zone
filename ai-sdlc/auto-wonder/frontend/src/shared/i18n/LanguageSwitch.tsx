import { Button, Dropdown } from 'antd';
import { GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useLocale } from './LocaleProvider';
export function LanguageSwitch() {
  const { locale, setLocale } = useLocale();
  const { t } = useTranslation('common');
  return <Dropdown trigger={['click']} placement="bottomRight" menu={{
    selectable: true, selectedKeys: [locale],
    items: [{ key: 'zh-CN', label: '中文' }, { key: 'en-US', label: 'English' }],
    onClick: ({ key }) => { if (key === 'zh-CN' || key === 'en-US') setLocale(key); },
  }}><Button icon={<GlobalOutlined aria-hidden="true" />} aria-label={t('language.switch')} title={t('language.switch')} /></Dropdown>;
}
