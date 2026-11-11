import { useEffect, useRef, useState } from 'react';
import { Alert, Badge, Button, Select, Tag } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { buildExecutorLaunchCommand } from '@/features/executor/api';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import * as api from './api';

// Keep these menus above the guide card (1100) and below the guide dialog (1200).
const guideSelectPopup = {
  popupMatchSelectWidth: false,
  styles: { popup: { root: { zIndex: 1150 } } },
};

export interface ChiefGuidedSetupProps {
  setup: api.SetupStatus;
  substep: number;
  reviewing?: boolean;
  clientKind: string;
  onClientKindChange: (value: string) => void;
  os: 'posix' | 'windows';
  onOsChange: (value: 'posix' | 'windows') => void;
  command: string | null;
  onCommandChange: (value: string | null) => void;
  onAdvance: () => void;
  onSetupCompleted?: (setup: api.SetupStatus) => Promise<void> | void;
  onSetupError?: (message: string | null) => void;
  onCheckStatus: () => void;
  onResetWait: () => void;
  waitExpired: boolean;
}

/**
 * 引导中的 Chief 准备区：只渲染当前子步骤的目标控件，其他控件（历史、新会话、发送）
 * 在阶段 1 不出现，蒙版因此不需要拦截被遮挡的按钮。所有完成判断都来自真实接口与 setup 状态。
 */
export function ChiefGuidedSetup({
  setup,
  substep,
  reviewing,
  clientKind,
  onClientKindChange,
  os,
  onOsChange,
  command,
  onCommandChange,
  onAdvance,
  onSetupCompleted,
  onSetupError,
  onCheckStatus,
  onResetWait,
  waitExpired,
}: ChiefGuidedSetupProps) {
  const { t } = useTranslation('workspaceGuide');
  const [busy, setBusy] = useState(false);
  const [error, setInlineError] = useState<string | null>(null);
  const [copyFailed, setCopyFailed] = useState(false);
  const autoAdvanced = useRef<number | null>(null);

  const allConfigured = setup.agentConfigured && setup.skillsConfigured
    && setup.executorConfigured && setup.launchConfigured;
  const satisfied = substep === 0 ? setup.executorConfigured : substep === 1 ? allConfigured : false;

  useEffect(() => {
    if (reviewing || !satisfied) {
      autoAdvanced.current = null;
      return;
    }
    if (autoAdvanced.current === substep) {
      return;
    }
    autoAdvanced.current = substep;
    onAdvance();
  }, [onAdvance, reviewing, satisfied, substep]);

  function setError(message: string | null) {
    if (onSetupError) onSetupError(message);
    else setInlineError(message);
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : t('setup.actionFailed'));
    } finally {
      setBusy(false);
    }
  }

  function completeSetup() {
    void run(async () => {
      const updated = await api.completeSetup(clientKind);
      // Publishing the new setup must not also trigger this step's auto-advance effect.
      autoAdvanced.current = substep;
      await onSetupCompleted?.(updated);
      onAdvance();
    });
  }

  function generateCommand(advanceAfter: boolean) {
    if (!setup.executorId) {
      setError(t('setup.actionFailed'));
      return;
    }
    void run(async () => {
      const result = await buildExecutorLaunchCommand(setup.executorId!, { os });
      onCommandChange(result.command);
      if (advanceAfter) {
        onAdvance();
      }
    });
  }

  function copyCommand() {
    if (!command) {
      setError(t('setup.actionFailed'));
      return;
    }
    const text = command;
    void run(async () => {
      const copied = await copyTextToClipboard(text);
      if (copied) {
        setCopyFailed(false);
        onAdvance();
      } else {
        setCopyFailed(true);
      }
    });
  }

  return <section className="aw-chief-setup aw-chief-setup-guided" data-guide-setup aria-label={t('stage.configure.title')}>
    <span className="aw-guide-secondary">{t('phaseProgress', { stage: 1, substep: substep + 1 })}</span>
    <h3>{substep === 0 ? t('substep.client')
      : substep === 1 ? t('substep.completeSetup')
        : substep === 2 ? t('substep.hostOs')
          : substep === 3 ? t('substep.generate')
            : substep === 4 ? t('substep.copy')
              : t('substep.waitOnline')}</h3>

    {substep === 0 && (setup.executorConfigured
      ? <p>{t('setup.existingClient')}</p>
      : <Select aria-label={t('setup.clientLabel')} value={clientKind} onChange={onClientKindChange}
        {...guideSelectPopup}
        options={[{ value: 'QODER_CLI', label: t('setup.clientQoder') },
          { value: 'QODER_CN_CLI', label: t('setup.clientQoderCn') }]} />)}
    {substep === 0 && !setup.executorConfigured
      && <Button type="primary" onClick={onAdvance}>{t('setup.confirmClient')}</Button>}
    {substep === 0 && setup.executorConfigured && <Tag>{t('setup.existingStep')}</Tag>}

    {substep === 1 && (allConfigured
      ? <Tag>{t('setup.existingStep')}</Tag>
      : <Button type="primary" loading={busy} onClick={completeSetup}>{t('setup.completeAction')}</Button>)}

    {substep === 2 && <>
      <p>{t('setup.chooseOs')}</p>
      <Select aria-label={t('setup.osLabel')} value={os} onChange={onOsChange}
        {...guideSelectPopup}
        options={[{ value: 'posix', label: t('setup.posix') }, { value: 'windows', label: t('setup.windows') }]} />
      <Button type="primary" onClick={onAdvance}>{t('setup.confirmOs')}</Button>
    </>}

    {substep === 3 && <Button type="primary" loading={busy} onClick={() => generateCommand(true)}>
      {t('setup.generateAction')}
    </Button>}

    {substep === 4 && <>
      {command
        ? <div className="aw-chief-guide-command">
          <Button type="text" icon={<CopyOutlined />} loading={busy}
            aria-label={t('setup.copyIcon')} title={t('setup.copyIcon')} onClick={copyCommand} />
          <pre>{command}</pre>
        </div>
        : <Button loading={busy} onClick={() => generateCommand(false)}>{t('setup.generateAction')}</Button>}
      <Button type="primary" loading={busy} disabled={!command} onClick={copyCommand}>{t('setup.copyAction')}</Button>
      <p>{t('setup.copiedHint')}</p>
      {copyFailed && <>
        <Alert type="error" message={t('setup.copyFailed')} />
        <Button onClick={onAdvance}>{t('manualCopied')}</Button>
      </>}
    </>}

    {substep === 5 && <>
      <p>{t('setup.waiting')} {!waitExpired && !reviewing && t('setup.autoCheckHint')}</p>
      <div className="aw-chief-guide-check">
        <Button loading={busy} onClick={onCheckStatus}>{t('setup.checkStatus')}</Button>
        <span role="status">
          <Badge status={waitExpired ? 'default' : 'processing'}
            text={waitExpired ? t('setup.autoCheckPaused') : t('setup.autoChecking')} />
        </span>
      </div>
      {waitExpired && <>
        <Alert type="warning" message={t('setup.waitExpired')} />
        <Button onClick={onResetWait}>{t('retry')}</Button>
      </>}
    </>}

    {error && <Alert type="error" message={error} />}
  </section>;
}
