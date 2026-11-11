import { Button, message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { setupStatus } from '@/features/chiefOfStaff/api';
import { ChiefIcon } from '@/features/chiefOfStaff/ChiefIcon';
import { useAuthStore } from '@/shared/auth/store';
import {
  notifyGuideEntryClicked,
  openChiefPanel,
  useGuideRuntime,
} from '@/features/workspaceGuide/bridge';

/**
 * 顶部帮助中心左侧的 Chief 机器人入口。平时只打开 Chief；
 * 引导阶段 2 点击时才强制进入 existing expanded 全屏模式并推进引导。
 */
export function ChiefEntryButton({ standalone = false }: { standalone?: boolean }) {
  const { t } = useTranslation('workspaceGuide');
  const runtime = useGuideRuntime();
  const guidedEntry = !standalone && runtime.active && runtime.stage === 2;
  const [checking, setChecking] = useState(false);
  const pending = useRef<symbol>();
  useEffect(() => () => { pending.current = undefined; }, []);

  const handleClick = async () => {
    if (!standalone) {
      if (guidedEntry) notifyGuideEntryClicked();
      openChiefPanel({ expanded: guidedEntry });
      return;
    }
    const { user, currentWorkspace } = useAuthStore.getState();
    if (!user || !currentWorkspace) {
      void message.info(t('entryChooseWorkspace'));
      return;
    }
    if (pending.current) return;
    const request = Symbol();
    pending.current = request;
    setChecking(true);
    const isCurrent = () => {
      const current = useAuthStore.getState();
      return pending.current === request
        && current.user?.id === user.id && current.currentWorkspace?.id === currentWorkspace.id;
    };
    try {
      const setup = await setupStatus();
      if (!isCurrent()) return;
      if (setup.ready) openChiefPanel({ expanded: false });
      else void message.info(t('entrySetupRequired'));
    } catch {
      if (isCurrent()) void message.error(t('entryCheckFailed'));
    } finally {
      if (pending.current === request) {
        pending.current = undefined;
        setChecking(false);
      }
    }
  };

  return <Button
    type="text"
    className={`aw-chief-entry${guidedEntry ? ' aw-guide-focus' : ''}`}
    data-guide-target={guidedEntry ? 'entry' : undefined}
    aria-label={t('entry')}
    title={t('entry')}
    icon={<ChiefIcon size={18} />}
    loading={checking}
    onClick={handleClick}
  />;
}
