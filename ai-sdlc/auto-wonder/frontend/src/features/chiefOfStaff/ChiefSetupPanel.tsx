import { Button, Select, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SetupStatus } from './api';

const checklist = ['agentConfigured', 'skillsConfigured', 'executorConfigured', 'launchConfigured'] as const;

export interface ChiefSetupPanelProps {
  setup: SetupStatus;
  admin: boolean;
  clientKind: string;
  onClientKindChange: (value: string) => void;
  os: 'posix' | 'windows';
  onOsChange: (value: 'posix' | 'windows') => void;
  command: string | null;
  busy: boolean;
  onComplete: () => void;
  onGenerate: () => void;
  onNavigateAgent: () => void;
  onNavigateExecutors: () => void;
}

/**
 * Chief 的准备区域。普通模式保留原有的权限、错误和在线判断；
 * 引导模式由 ChiefGuidedSetup 复用同一份 setup 数据逐子步骤突出当前目标。
 */
export function ChiefSetupPanel({
  setup,
  admin,
  clientKind,
  onClientKindChange,
  os,
  onOsChange,
  command,
  busy,
  onComplete,
  onGenerate,
  onNavigateAgent,
  onNavigateExecutors,
}: ChiefSetupPanelProps) {
  const { t } = useTranslation('chiefOfStaff');
  return <section className="aw-chief-setup">
    <h3>{t('setupTitle')}</h3>
    <p>{t('setupDescription')}</p>
    {checklist.map(field => <div className="aw-chief-check" key={field}>
      <span>{t(field)}</span>
      <Tag>{setup[field] ? t('configured') : t('missing')}</Tag>
    </div>)}
    {!admin ? <p>{t('contactAdmin')}</p> : <>
      {!setup.executorConfigured && <Select aria-label={t('provider')} value={clientKind} onChange={onClientKindChange}
        options={[{ value: 'QODER_CLI', label: t('qoder') }, { value: 'QODER_CN_CLI', label: t('qoderCn') }]} />}
      {(!setup.skillsConfigured || !setup.executorConfigured || !setup.launchConfigured)
        && <Button type="primary" loading={busy} onClick={onComplete}>{t('completeSetup')}</Button>}
      {setup.agentId && <Button onClick={onNavigateAgent}>{t('agentConfiguration')}</Button>}
      {setup.executorConfigured && <>
        <p>{t('startHint')}</p>
        <Select aria-label={t('os')} value={os} onChange={onOsChange}
          options={[{ value: 'posix', label: t('posix') }, { value: 'windows', label: t('windows') }]} />
        <Button loading={busy} onClick={onGenerate}>{t('launchCommand')}</Button>
        {command && <Typography.Paragraph copyable={{ text: command }}><pre>{command}</pre></Typography.Paragraph>}
        <Button onClick={onNavigateExecutors}>{t('executors')}</Button>
      </>}
    </>}
  </section>;
}
