import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useState, useEffect, useRef, type CSSProperties } from 'react';
import { Alert, Button, Card, Form, Input, Typography, message, Spin, Tabs } from 'antd';
import {
  BgColorsOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  RestOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { PageHeader } from '@/shared/ui/PageHeader';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAuthStore } from '@/shared/auth/store';
import type { WorkspaceInfo } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { myWorkspacesQueryKey } from './api';
import { AllWorkspacesTab } from './AllWorkspacesTab';
import { useSwitchWorkspace } from './useSwitchWorkspace';
import { SpaceDashboardPage } from '@/features/spaceDashboard/SpaceDashboardPage';
import { SpaceDashboardForbidden } from '@/features/spaceDashboard/components/SpaceDashboardForbidden';
import { WorkspaceEditModal } from './WorkspaceEditModal';
import { WorkspaceDeleteModal } from './WorkspaceDeleteModal';
import './workspaceLifecycle.css';

const { Title, Text } = Typography;
const { TextArea } = Input;

const ACCENT_COLOR = 'var(--aw-accent-text)';
const BORDER_COLOR = 'var(--aw-border)';
const WORKSPACE_CARD_SHADOW = '0 0 0 1px var(--aw-accent-text)';
/** Deep links may only return to the channel integration page this feature owns. */
const ALLOWED_RETURN_TO = '/integrations?tab=channels';

function safeReturnTo(value: string | null): string | null {
  return value === ALLOWED_RETURN_TO ? value : null;
}

export function WorkspaceSelectPage() {
  const { t } = useTranslation('auth');
  const [creating, setCreating] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editTarget, setEditTarget] = useState<WorkspaceInfo | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<WorkspaceInfo | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const targetWorkspaceId = Number(searchParams.get('targetWorkspaceId')) || null;
  const returnTo = safeReturnTo(searchParams.get('returnTo'));
  const deepLinkAttempted = useRef(false);
  const [deepLinkDenied, setDeepLinkDenied] = useState(false);
  const switchWorkspace = useSwitchWorkspace();
  const clearCurrentWorkspace = useAuthStore((s) => s.clearCurrentWorkspace);
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const user = useAuthStore((s) => s.user);
  const [form] = Form.useForm();
  useLocalizedValidation(form, showCreateForm);
  const { data: workspaces, isLoading, refetch } = useQuery({
    queryKey: myWorkspacesQueryKey(user?.id ?? null),
    queryFn: async () => {
      const resp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
      return resp.data;
    },
  });

  // 入口可见性只影响 Tab 是否出现，安全边界在服务端（非管理员调用接口返回 403）。
  const canSeeDashboard = user?.isAdmin === true
    || (workspaces ?? []).some((workspace) => workspace.canManage === true);
  const requestedTab = searchParams.get('tab')
    ?? (location.pathname === '/workspaces/dashboard' ? 'dashboard' : 'mine');
  const knownTab = requestedTab === 'all' || requestedTab === 'dashboard' ? requestedTab : 'mine';
  // 无管理权限却直接访问看板路径时按 requirement-spec §3.1/§4.7/§6-3 展示无权限空态，
  // 不再静默回落到「我的工作空间」；Tab 本身仍对非管理员隐藏。
  const dashboardDenied = knownTab === 'dashboard' && workspaces !== undefined && !canSeeDashboard;
  // 列表未加载完成前先停留在「我的工作空间」，避免无权限判定尚未成立时提前挂载看板请求。
  const activeTab = knownTab === 'dashboard' && workspaces === undefined ? 'mine' : knownTab;

  const handleSwitch = (workspace: WorkspaceInfo, destination: string = '/') =>
    switchWorkspace(workspace, destination);

  // Cross-Workspace deep link: only a Workspace the caller is actually a member of is switched to,
  // and the return route is limited to the channel page. A missing membership never falls back to
  // the current Workspace.
  useEffect(() => {
    if (!targetWorkspaceId || deepLinkAttempted.current || !workspaces) {
      return;
    }
    deepLinkAttempted.current = true;
    const target = workspaces.find((workspace) => workspace.id === targetWorkspaceId);
    if (!target) {
      setDeepLinkDenied(true);
      return;
    }
    handleSwitch(target, returnTo ?? '/').then((switched) => {
      if (!switched) {
        setDeepLinkDenied(true);
      }
    });
    // handleSwitch only changes identity-scoped state and navigates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetWorkspaceId, workspaces]);

  const handleCreate = async (values: { name: string; description?: string; background?: string }) => {
    setCreating(true);
    try {
      const resp = await apiClient.post<WorkspaceInfo>('/api/workspaces', values);
      await refetch();
      await handleSwitch(resp.data);
    } catch (e) {
      if (e instanceof ApiError) {
        message.error(e.message);
      }
    } finally {
      setCreating(false);
    }
  };

  // F6: the access token stays bound to a workspace that AuthFilter now rejects on every
  // workspace-scoped call, so the binding is dropped at the moment of deletion rather than
  // after the first failed request.
  const handleDeleted = (deleted: WorkspaceInfo) => {
    if (currentWorkspace?.id === deleted.id) {
      clearCurrentWorkspace();
    }
  };

  // 看板 Tab 用独立路径便于深链与分享；其余 Tab 走 ?tab= 与 URL 双向同步。
  const handleTabChange = (key: string) => {
    const next = new URLSearchParams(searchParams);
    next.delete('tab');
    if (key === 'dashboard') {
      navigate({ pathname: '/workspaces/dashboard', search: next.toString() });
      return;
    }
    next.set('tab', key);
    navigate({ pathname: '/workspaces', search: next.toString() });
  };

  return (
    <div style={pageShellStyle}>
      <PageHeader brandTo="/workspaces" />
      <div style={{ padding: 24 }}>
        <div style={headerStyle}>
          <Title level={2} style={{ margin: 0, color: 'var(--aw-text)', letterSpacing: 0 }}>{t('workspace.select')}</Title>
          <Button icon={<BgColorsOutlined />} onClick={() => navigate('/workspaces/branding')}>{t('workspace.platformSettings')}</Button>
        </div>

        {deepLinkDenied && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message={t('workspace.deepLinkDenied')}
          />
        )}

        {dashboardDenied && <SpaceDashboardForbidden />}

        {!dashboardDenied && (
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          tabBarExtraContent={
          <button
            type="button"
            className="aw-recycle-bin-entry"
            data-testid="workspace-recycle-bin-entry"
            aria-label={t('workspace.openRecycleBin')}
            onClick={() => navigate('/workspaces/recycle-bin')}
          >
            <RestOutlined />{t('workspace.recycleBin')}</button>
          }
          items={[
            {
              key: 'mine',
              label: t('workspace.mine'),
              children: (
                <>
                  {isLoading ? (
                    <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
                  ) : workspaces && workspaces.length > 0 ? (
                    <div data-testid="workspace-select-grid" style={orgGridStyle}>
                      {workspaces.map((workspace) => {
                        const active = currentWorkspace?.id === workspace.id;
                        return (
                          <div
                            key={workspace.id}
                            data-testid={`workspace-card-shell-${workspace.id}`}
                            style={getOrgCardStyle(active)}
                            onMouseEnter={(event) => {
                              event.currentTarget.style.borderColor = ACCENT_COLOR;
                              event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
                              event.currentTarget.style.transform = 'translateY(-1px)';
                            }}
                            onMouseLeave={(event) => {
                              const nextStyle = getOrgCardStyle(active);
                              event.currentTarget.style.borderColor = String(nextStyle.borderColor);
                              event.currentTarget.style.boxShadow = String(nextStyle.boxShadow);
                              event.currentTarget.style.transform = String(nextStyle.transform || 'none');
                            }}
                            onFocus={(event) => {
                              event.currentTarget.style.borderColor = ACCENT_COLOR;
                              event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
                            }}
                            onBlur={(event) => {
                              const nextStyle = getOrgCardStyle(active);
                              event.currentTarget.style.borderColor = String(nextStyle.borderColor);
                              event.currentTarget.style.boxShadow = String(nextStyle.boxShadow);
                            }}
                          >
                            {active && <span style={currentBadgeStyle}>{t('workspace.current')}</span>}
                            <button
                              type="button"
                              data-testid={`workspace-card-${workspace.id}`}
                              style={cardEnterStyle}
                              aria-label={t('workspace.enterName', { name: workspace.name })}
                              onClick={() => handleSwitch(workspace)}
                            >
                              <span style={orgMarkStyle}>{getOrgInitial(workspace.name)}</span>
                              <EllipsisText tooltip={workspace.name} style={orgNameStyle}>{workspace.name}</EllipsisText>
                              <EllipsisText tooltip={workspace.description || t('workspace.noDescription')} lines={2} style={orgDescStyle}>{workspace.description || t('workspace.noDescription')}</EllipsisText>
                            </button>
                            {/* F1.2: siblings of the enter button rather than children of it. A
                                control nested inside a <button> is invalid HTML and its click
                                would reach the card's enter handler. canManage comes from the
                                server (owner or ADMIN) so the UI cannot disagree with it. */}
                            {workspace.canManage === true && (
                              <div
                                style={cardManageAreaStyle}
                                data-testid={`workspace-manage-area-${workspace.id}`}
                              >
                                <button
                                  type="button"
                                  className="aw-card-manage-button"
                                  data-testid={`edit-workspace-${workspace.id}`}
                                  aria-label={t('workspace.editName', { name: workspace.name })}
                                  onClick={() => setEditTarget(workspace)}
                                >
                                  <EditOutlined />
                                </button>
                                <button
                                  type="button"
                                  className="aw-card-manage-button aw-card-manage-button--danger"
                                  data-testid={`delete-workspace-${workspace.id}`}
                                  aria-label={t('workspace.deleteName', { name: workspace.name })}
                                  onClick={() => setDeleteTarget(workspace)}
                                >
                                  <DeleteOutlined />
                                </button>
                              </div>
                            )}
                          </div>
                        );
                      })}

                      {!showCreateForm && (
                        <button
                          type="button"
                          data-testid="workspace-create-card"
                          style={createCardStyle}
                          onClick={() => setShowCreateForm(true)}
                        >
                          <span style={plusMarkStyle}><PlusOutlined /></span>
                          <span style={orgNameStyle}>{t('workspace.create')}</span>
                          <span style={{ ...orgDescStyle, textAlign: 'center' }}>{t('workspace.initialize')}</span>
                        </button>
                      )}
                    </div>
                  ) : (
                    <div data-testid="workspace-select-grid" style={orgGridStyle}>
                      <div style={emptyStateStyle}>
                        <Text type="secondary">{t('workspace.emptyMine')}</Text>
                      </div>
                      {!showCreateForm && (
                        <button type="button" data-testid="workspace-create-card" style={createCardStyle} onClick={() => setShowCreateForm(true)}>
                          <span style={plusMarkStyle}><PlusOutlined /></span>
                          <span style={orgNameStyle}>{t('workspace.create')}</span>
                          <span style={{ ...orgDescStyle, textAlign: 'center' }}>{t('workspace.initialize')}</span>
                        </button>
                      )}
                    </div>
                  )}

                  {showCreateForm && (
                    <Card style={createFormCardStyle} styles={{ body: { padding: 18 } }}>
                      <Form form={form} onFinish={handleCreate} layout="vertical">
                        <Form.Item
                          name="name"
                          label={t('workspace.name')}
                          rules={[{ required: true, message: t('workspace.nameRequired') }]}
                        >
                          <Input placeholder={t('workspace.namePlaceholder')} maxLength={128} />
                        </Form.Item>
                        <Form.Item name="description" label={t('workspace.description')}>
                          <Input placeholder={t('workspace.descriptionPlaceholder')} maxLength={512} />
                        </Form.Item>
                        <Form.Item name="background" label={t('workspace.background')}>
                          <TextArea placeholder={t('workspace.backgroundPlaceholder')} rows={3} />
                        </Form.Item>
                        <Form.Item>
                          <Button
                            type="primary"
                            htmlType="submit"
                            loading={creating}
                            style={{ marginRight: 8 }}
                          >{t('action.create')}</Button>
                          <Button onClick={() => { setShowCreateForm(false); form.resetFields(); }}>{t('action.cancel')}</Button>
                        </Form.Item>
                      </Form>
                    </Card>
                  )}
                </>
              ),
            },
            {
              key: 'all',
              label: t('workspace.all'),
              children: <AllWorkspacesTab />,
            },
            ...(canSeeDashboard ? [{
              key: 'dashboard',
              label: <span data-testid="workspace-dashboard-tab">{t('workspace.spaceDashboard')}</span>,
              children: <SpaceDashboardPage />,
            }] : []),
          ]}
        />
        )}

        <WorkspaceEditModal workspace={editTarget} onClose={() => setEditTarget(null)} />
        <WorkspaceDeleteModal
          workspace={deleteTarget}
          onDeleted={handleDeleted}
          onClose={() => setDeleteTarget(null)}
        />
      </div>
    </div>
  );
}

function getOrgInitial(name: string) {
  return name.trim().slice(0, 2).toUpperCase() || i18n.t('auth:workspace.emptyName');
}

function getOrgCardStyle(active: boolean): CSSProperties {
  return {
    ...orgCardStyle,
    borderColor: active ? ACCENT_COLOR : 'var(--aw-border)',
    boxShadow: active ? WORKSPACE_CARD_SHADOW : 'none',
  };
}

const pageShellStyle: CSSProperties = {
  minHeight: '100vh',
  background: 'var(--aw-bg)',
};

const headerStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 24,
  marginBottom: 12,
  flexWrap: 'wrap',
};

const orgGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
  gap: 16,
};

const orgCardStyle: CSSProperties = {
  minWidth: 0,
  position: 'relative',
  minHeight: 178,
  border: '1px solid var(--aw-border)',
  background: 'var(--aw-panel)',
  borderRadius: 8,
  padding: 18,
  cursor: 'pointer',
  textAlign: 'left',
  appearance: 'none',
  transition: 'border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease',
};

// The enter control now lives inside the card shell, so it sheds every piece of native button
// chrome and lets the shell own the border, background and hover transform.
const cardEnterStyle: CSSProperties = {
  display: 'block',
  width: '100%',
  padding: 0,
  // `0` rather than the idiomatic `'none'`: jsdom drops the `border: none` shorthand, so the
  // computed border falls back to the UA default and this reset becomes unobservable in tests.
  // Both render identically in a browser.
  border: 0,
  background: 'transparent',
  color: 'inherit',
  font: 'inherit',
  textAlign: 'left',
  cursor: 'pointer',
  appearance: 'none',
};

// In normal flow below the enter control rather than absolutely positioned over it: an overlay
// would both hide part of the card and swallow the management clicks (acceptance #18).
const cardManageAreaStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 12,
};

const createCardStyle: CSSProperties = {
  ...orgCardStyle,
  borderStyle: 'dashed',
  borderColor: 'var(--aw-border)',
  background: 'var(--aw-raised)',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  textAlign: 'center',
};

const orgMarkStyle: CSSProperties = {
  width: 42,
  height: 42,
  borderRadius: 8,
  display: 'grid',
  placeItems: 'center',
  marginBottom: 18,
  background: 'var(--aw-primary)',
  color: 'var(--aw-primary-ink)',
  fontWeight: 800,
  fontSize: 16,
};

const plusMarkStyle: CSSProperties = {
  width: 44,
  height: 44,
  borderRadius: 8,
  border: '1px solid var(--aw-border)',
  color: ACCENT_COLOR,
  background: 'var(--aw-panel)',
  display: 'grid',
  placeItems: 'center',
  fontSize: 24,
  marginBottom: 16,
};

const currentBadgeStyle: CSSProperties = {
  position: 'absolute',
  top: 12,
  right: 12,
  padding: '3px 8px',
  borderRadius: 999,
  background: 'var(--aw-primary)',
  color: 'var(--aw-primary-ink)',
  fontSize: 12,
  fontWeight: 700,
};

const orgNameStyle: CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  display: 'block',
  color: 'var(--aw-text)',
  fontSize: 18,
  fontWeight: 700,
  lineHeight: 1.3,
  marginBottom: 8,
};

const orgDescStyle: CSSProperties = {
  display: '-webkit-box',
  WebkitLineClamp: 2,
  WebkitBoxOrient: 'vertical',
  overflow: 'hidden',
  overflowWrap: 'anywhere',
  color: 'var(--aw-muted)',
  fontSize: 13,
  lineHeight: 1.6,
  minHeight: 42,
};

const emptyStateStyle: CSSProperties = {
  minHeight: 178,
  border: `1px dashed ${BORDER_COLOR}`,
  background: 'var(--aw-panel)',
  borderRadius: 8,
  display: 'grid',
  placeItems: 'center',
  padding: 18,
};

const createFormCardStyle: CSSProperties = {
  marginTop: 18,
  borderColor: BORDER_COLOR,
  boxShadow: 'none',
};
