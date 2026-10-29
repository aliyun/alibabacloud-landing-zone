import { useTranslation } from 'react-i18next';
import { Alert } from 'antd';
import { GROUP_INFO_COMMAND } from './channelProtocol';

interface Props {
  provider: 'DINGTALK' | 'FEISHU' | string;
}

/**
 * Shared three-step guide for obtaining a group ID. The bot name is not guessed: it is always the
 * platform-configured group-chat bot, and the command literal stays 群信息 in every language.
 */
export function GroupIdGuide({ provider }: Props) {
  const { t } = useTranslation('integration');
  const feishu = provider === 'FEISHU';
  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message={t('groupIdGuideTitle')}
      description={
        <div style={{ display: 'grid', gap: 4 }}>
          <span>{t('groupIdGuideIntro')}</span>
          <span>{t('groupIdGuideStepAt')}</span>
          <span>{t('groupIdGuideStepInfo', { command: GROUP_INFO_COMMAND })}</span>
          <span>{t('groupIdGuideStepCopy')}</span>
          <span style={{ marginTop: 4 }}>
            {feishu ? t('groupIdGuideFeishu') : t('groupIdGuideDingTalk')}
          </span>
          <span>{t('groupIdGuideUnresponsive')}</span>
        </div>
      }
    />
  );
}
