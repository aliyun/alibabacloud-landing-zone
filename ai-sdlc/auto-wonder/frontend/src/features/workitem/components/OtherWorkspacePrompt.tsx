import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Result, Space, message } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { ApiError } from '@/shared/types/common';
import type { SwitchWorkspaceResponse } from '@/shared/types/common';
import { useAuthStore } from '@/shared/auth/store';
import { refreshTenantScopedQueries } from '../queryCache';
import type { WorkitemOwningWorkspace } from '../otherWorkspace';

interface OtherWorkspacePromptProps {
  owning: WorkitemOwningWorkspace;
  /** 服务端原始诊断，取消切换后作为普通错误态的第二行展示。 */
  errorMessage?: string;
}

/**
 * 跨项目工单链接的显式确认入口（工单 55699）：用户对归属项目有权限时，先问是否切换，
 * 而不是把链接降级成「工单不存在」。确认后原地切换并重载，URL 与定位参数保持不变。
 */
export function OtherWorkspacePrompt({ owning, errorMessage }: OtherWorkspacePromptProps) {
  const { t } = useTranslation('workitem');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentWorkspace = useAuthStore((s) => s.setCurrentWorkspace);
  const [dismissed, setDismissed] = useState(false);
  const [switching, setSwitching] = useState(false);

  const handleSwitch = async () => {
    if (switching) return;
    setSwitching(true);
    try {
      const resp = await apiClient.post<SwitchWorkspaceResponse>(
        `/api/workspaces/${owning.workspaceId}/switch`,
      );
      setAccessToken(resp.data.accessToken);
      setCurrentWorkspace(
        { id: owning.workspaceId, name: owning.workspaceName, description: '' },
        resp.data.accessLevel,
      );
      // 不导航：URL 与查询参数原样保留，租户域缓存重置后当前详情原地重新拉取
      await refreshTenantScopedQueries(queryClient);
    } catch (e) {
      // 保留确认入口：权限失效或网络失败时提示原因，绝不静默切换
      message.error(e instanceof ApiError ? e.message : t('detail.otherWorkspaceSwitchFailed'));
    } finally {
      setSwitching(false);
    }
  };

  if (dismissed) {
    return (
      <Result
        status="error"
        title={t('detail.loadFailed')}
        subTitle={errorMessage || t('detail.retryLater')}
        extra={
          <Space>
            <Button onClick={() => setDismissed(false)}>
              {t('detail.otherWorkspaceSwitch', { name: owning.workspaceName })}
            </Button>
            <Button onClick={() => navigate(-1)}>{t('detail.back')}</Button>
          </Space>
        }
      />
    );
  }
  return (
    <Result
      status="warning"
      title={t('detail.otherWorkspaceTitle', { name: owning.workspaceName })}
      subTitle={t('detail.otherWorkspaceHint')}
      extra={
        <Space>
          <Button type="primary" loading={switching} onClick={handleSwitch}>
            {t('detail.otherWorkspaceSwitch', { name: owning.workspaceName })}
          </Button>
          <Button disabled={switching} onClick={() => setDismissed(true)}>
            {t('detail.cancel')}
          </Button>
        </Space>
      }
    />
  );
}
