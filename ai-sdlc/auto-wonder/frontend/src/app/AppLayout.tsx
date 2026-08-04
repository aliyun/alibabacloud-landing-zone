import { useCallback, useEffect, useRef, useState } from 'react';
import { Layout, Dropdown, Typography, Button, Drawer, Grid, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckOutlined, LoadingOutlined, LogoutOutlined, DownOutlined, MenuFoldOutlined, MenuUnfoldOutlined, UserOutlined } from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Sidebar, NAV_GROUPS, navItemMatchesPath } from './Sidebar';
import { NotificationBell } from '@/shared/ui/NotificationBell';
import { useAuthStore } from '@/shared/auth/store';
import { refreshCurrentMembership } from '@/shared/auth/refreshCurrentMembership';
import { logout } from '@/features/auth/api';
import { apiClient } from '@/shared/api/client';
import type { OrgInfo, SwitchOrgResponse, UserInfo } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;
const { useBreakpoint } = Grid;

const NAV_ITEMS = NAV_GROUPS.flatMap((group) => group.items);

const SIDEBAR_COLLAPSED_KEY = 'autowonder.sidebar.collapsed';

function readCollapsedPref(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

function writeCollapsedPref(collapsed: boolean) {
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0');
  } catch {
    /* ignore */
  }
}

export function shouldUseMobileLayout(screens: Record<string, boolean | undefined>) {
  return Boolean(screens.xs && !screens.md);
}

export function buildHeaderContext(pathname: string, orgName: string) {
  const pageItem = [...NAV_ITEMS]
    .filter((item) => navItemMatchesPath(item, pathname))
    .sort((a, b) => b.key.length - a.key.length)[0];

  if (!pageItem) {
    return { orgName, sectionTitle: '', pageTitle: '' };
  }

  const sectionItem = [...NAV_ITEMS]
    .filter((item) => item.key !== pageItem.key && pageItem.key.startsWith(`${item.key}/`))
    .sort((a, b) => b.key.length - a.key.length)[0];

  return {
    orgName,
    sectionTitle: sectionItem?.label || '',
    pageTitle: pageItem.label,
  };
}

export function buildUserDisplay(user: UserInfo | null) {
  const primaryText = user?.nickname?.trim() || user?.username?.trim() || '未登录用户';
  const secondaryText = user?.email?.trim() || user?.username?.trim() || '';
  return {
    primaryText,
    secondaryText,
    avatarText: primaryText[0]?.toUpperCase() || 'U',
  };
}

export function getOrgDeepLinkId(search: string): number | null {
  const value = new URLSearchParams(search).get('orgId');
  if (!value || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

export function removeOrgDeepLink(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('orgId');
  const nextSearch = params.toString();
  return nextSearch ? `?${nextSearch}` : '';
}

const ellipsisTextStyle = {
  display: 'block',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
} as const;

const orangeWhiteInitialStyle = {
  background: 'linear-gradient(135deg, #fff7ed 0%, #ffffff 100%)',
  border: '1px solid rgba(255, 106, 0, 0.28)',
  color: '#ff6a00',
  boxShadow: '0 2px 8px rgba(255, 106, 0, 0.10)',
} as const;

export function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const currentOrg = useAuthStore((s) => s.currentOrg);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const clear = useAuthStore((s) => s.clear);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentOrg = useAuthStore((s) => s.setCurrentOrg);

  const [collapsed, setCollapsed] = useState<boolean>(readCollapsedPref);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const screens = useBreakpoint();
  const isMobile = shouldUseMobileLayout(screens);

  useEffect(() => {
    const refresh = () => {
      void refreshCurrentMembership();
    };
    refresh();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);

  const { data: branding = DEFAULT_BRANDING } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });

  const { data: orgs } = useQuery({
    queryKey: ['orgs', 'mine'],
    queryFn: async () => {
      const resp = await apiClient.get<OrgInfo[]>('/api/orgs/mine');
      return resp.data;
    },
  });
  const [switchingOrgId, setSwitchingOrgId] = useState<number | null>(null);
  const switchingRef = useRef<number | null>(null);

  const handleOrgSwitch = useCallback(async (org: OrgInfo) => {
    if (org.id === currentOrg?.id || switchingRef.current === org.id) return;
    switchingRef.current = org.id;
    setSwitchingOrgId(org.id);
    try {
      const resp = await apiClient.post<SwitchOrgResponse>(`/api/orgs/${org.id}/switch`);
      setAccessToken(resp.data.accessToken);
      setCurrentOrg(org, resp.data.accessLevel);
      await refreshTenantScopedQueries(queryClient);
      message.success(`已切换到 ${org.name}`);
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '切换组织失败');
    } finally {
      switchingRef.current = null;
      setSwitchingOrgId(null);
    }
  }, [currentOrg?.id, setAccessToken, setCurrentOrg, queryClient]);

  useEffect(() => {
    const targetOrgId = getOrgDeepLinkId(location.search);
    if (!targetOrgId) {
      return;
    }
    const cleanPath = `${location.pathname}${removeOrgDeepLink(location.search)}${location.hash}`;
    if (currentOrg?.id === targetOrgId) {
      navigate(cleanPath, { replace: true });
      return;
    }
    let cancelled = false;
    async function switchToLinkedOrg() {
      try {
        const orgsResp = await apiClient.get<OrgInfo[]>('/api/orgs/mine');
        if (cancelled) {
          return;
        }
        const targetOrg = orgsResp.data.find((org) => org.id === targetOrgId);
        if (!targetOrg) {
          message.error('你不在该工单所属组织中');
          return;
        }
        const switchResp = await apiClient.post<SwitchOrgResponse>(`/api/orgs/${targetOrgId}/switch`);
        if (cancelled) {
          return;
        }
        setAccessToken(switchResp.data.accessToken);
        setCurrentOrg(targetOrg, switchResp.data.accessLevel);
        await refreshTenantScopedQueries(queryClient);
        navigate(cleanPath, { replace: true });
      } catch {
        if (!cancelled) {
          message.error('切换到工单所属组织失败');
        }
      }
    }
    void switchToLinkedOrg();
    return () => {
      cancelled = true;
    };
  }, [
    currentOrg?.id,
    location.hash,
    location.pathname,
    location.search,
    navigate,
    queryClient,
    setAccessToken,
    setCurrentOrg,
  ]);

  const toggleCollapsed = () => {
    if (isMobile) {
      setMobileMenuOpen(true);
      return;
    }
    setCollapsed((prev) => {
      const next = !prev;
      writeCollapsedPref(next);
      return next;
    });
  };

  const handleLogout = async () => {
    if (refreshToken) {
      try { await logout(refreshToken); } catch { /* ignore */ }
    }
    clear();
    navigate('/login');
  };

  const userMenuItems = [
    { key: 'profile-settings', label: '个人设置', icon: <UserOutlined />, onClick: () => navigate('/profile/settings') },
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, onClick: handleLogout },
  ];

  const orgMenuItems = [
    ...(orgs || []).map((org) => ({
      key: `org-${org.id}`,
      label: org.name,
      icon: org.id === currentOrg?.id
        ? (org.id === switchingOrgId ? <LoadingOutlined /> : <CheckOutlined style={{ color: '#ff6a00' }} />)
        : (org.id === switchingOrgId ? <LoadingOutlined /> : undefined),
      disabled: org.id === switchingOrgId,
      onClick: () => { void handleOrgSwitch(org); },
    })),
    { type: 'divider' as const },
    { key: 'manage-orgs', label: '管理组织...', onClick: () => navigate('/orgs') },
  ];

  const orgName = currentOrg?.name || '未选择';
  const { sectionTitle, pageTitle } = buildHeaderContext(location.pathname, orgName);
  const userDisplay = buildUserDisplay(user);
  const menuButtonLabel = isMobile ? '打开菜单' : collapsed ? '展开菜单' : '折叠菜单';

  return (
    <Layout style={{ height: '100vh', minWidth: 0 }}>
      {!isMobile && (
        <Sider
          width={220}
          collapsedWidth={72}
          collapsed={collapsed}
          theme="light"
          style={{ borderRight: '1px solid rgba(0,0,0,0.05)', overflow: 'auto' }}
        >
          <div style={{ height: 56, display: 'flex', alignItems: 'center', justifyContent: collapsed ? 'center' : 'flex-start', padding: collapsed ? 0 : '0 18px', borderBottom: '1px solid rgba(0,0,0,0.04)', gap: 10 }}>
            <img src={branding.logoUrl || '/logo.png'} width={28} height={28} style={{ flexShrink: 0, borderRadius: 6, objectFit: 'contain' }} alt={branding.platformName} />
            {!collapsed && <Text strong style={{ fontSize: 14, color: '#374151' }}>{branding.platformName}</Text>}
          </div>

          <Dropdown menu={{ items: orgMenuItems }} trigger={['click']} placement="bottomLeft">
            <div
              title={collapsed ? orgName : undefined}
              style={{
                margin: collapsed ? '8px auto' : '8px 12px',
                padding: collapsed ? 0 : '7px 10px',
                width: collapsed ? 36 : 'auto',
                height: collapsed ? 36 : 'auto',
                borderRadius: 7,
                border: '1px solid rgba(0,0,0,0.06)',
                background: '#fafbfc',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: collapsed ? 'center' : 'flex-start',
                gap: 8,
                minWidth: 0,
              }}
            >
              <div style={{
                width: 20, height: 20, borderRadius: 5,
                ...orangeWhiteInitialStyle,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontWeight: 600, fontSize: 11, flexShrink: 0,
              }}>
                {orgName[0]}
              </div>
              {!collapsed && (
                <>
                  <span
                    style={{
                      ...ellipsisTextStyle,
                      fontSize: 12,
                      flex: 1,
                      minWidth: 0,
                      color: '#374151',
                      fontWeight: 500,
                    }}
                    title={orgName}
                  >
                    {orgName}
                  </span>
                  <DownOutlined style={{ fontSize: 9, color: '#aaa' }} />
                </>
              )}
            </div>
          </Dropdown>

          <Sidebar collapsed={collapsed} />
        </Sider>
      )}

      <Drawer
        placement="left"
        open={mobileMenuOpen}
        onClose={() => setMobileMenuOpen(false)}
        width={280}
        styles={{ body: { padding: 0 } }}
      >
        <div style={{ height: 56, display: 'flex', alignItems: 'center', padding: '0 18px', borderBottom: '1px solid rgba(0,0,0,0.04)', gap: 10 }}>
          <img src={branding.logoUrl || '/logo.png'} width={28} height={28} style={{ flexShrink: 0, borderRadius: 6, objectFit: 'contain' }} alt={branding.platformName} />
          <Text strong style={{ fontSize: 14, color: '#374151' }}>{branding.platformName}</Text>
        </div>
        <Dropdown menu={{ items: orgMenuItems }} trigger={['click']} placement="bottomLeft">
          <div
            style={{
              margin: '8px 12px',
              padding: '7px 10px',
              borderRadius: 7,
              border: '1px solid rgba(0,0,0,0.06)',
              background: '#fafbfc',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              minWidth: 0,
            }}
          >
            <div style={{
              width: 20, height: 20, borderRadius: 5,
              ...orangeWhiteInitialStyle,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontWeight: 600, fontSize: 11, flexShrink: 0,
            }}>
              {orgName[0]}
            </div>
            <span
              style={{
                ...ellipsisTextStyle,
                fontSize: 12,
                flex: 1,
                minWidth: 0,
                color: '#374151',
                fontWeight: 500,
              }}
              title={orgName}
            >
              {orgName}
            </span>
            <DownOutlined style={{ fontSize: 9, color: '#aaa' }} />
          </div>
        </Dropdown>
        <Sidebar />
      </Drawer>

      <Layout style={{ minWidth: 0 }}>
        <Header style={{
          height: 60, padding: isMobile ? '0 12px' : '0 24px',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          borderBottom: '1px solid rgba(0,0,0,0.04)', background: '#fff',
          minWidth: 0,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0, flex: 1, overflow: 'hidden' }}>
            <Button
              type="text"
              onClick={toggleCollapsed}
              aria-label={menuButtonLabel}
              icon={isMobile || collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              style={{ fontSize: 16, color: '#6b7280', flexShrink: 0 }}
            />
            <Text
              style={{
                ...ellipsisTextStyle,
                fontSize: 13,
                color: '#9ca3af',
                fontWeight: 400,
                minWidth: 0,
                flex: 1,
              }}
              title={[sectionTitle, pageTitle].filter(Boolean).join(' / ')}
            >
              {sectionTitle ? (
                <>
                  <span style={{ color: '#6b7280' }}>{sectionTitle}</span>
                  <span style={{ margin: '0 6px', color: '#e5e7eb' }}>/</span>
                </>
              ) : null}
              <span style={{ color: '#374151' }}>{pageTitle}</span>
            </Text>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: isMobile ? 8 : 16, minWidth: 0, marginLeft: isMobile ? 8 : 16, flexShrink: 0 }}>
            <NotificationBell />
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" trigger={['click']}>
              <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: isMobile ? 0 : 10,
                padding: isMobile ? 4 : '6px 8px 6px 6px',
                borderRadius: 999,
                border: '1px solid rgba(0,0,0,0.06)',
                cursor: 'pointer',
                background: '#fff',
                minWidth: 0,
                maxWidth: 320,
              }}>
                <div style={{
                  width: 32, height: 32, borderRadius: '50%',
                  ...orangeWhiteInitialStyle,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 11, fontWeight: 600, flexShrink: 0,
                  boxShadow: '0 0 0 2px #fff, 0 0 0 3px rgba(255, 106, 0, 0.16)',
                }}>
                  {userDisplay.avatarText}
                </div>
                {!isMobile && (
                  <>
                    <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0, lineHeight: 1.1 }}>
                      <Text
                        strong
                        style={{
                          ...ellipsisTextStyle,
                          fontSize: 13,
                          color: '#111827',
                          maxWidth: 220,
                        }}
                        title={userDisplay.primaryText}
                      >
                        {userDisplay.primaryText}
                      </Text>
                      {userDisplay.secondaryText ? (
                        <Text
                          style={{
                            ...ellipsisTextStyle,
                            fontSize: 12,
                            color: '#9ca3af',
                            maxWidth: 220,
                          }}
                          title={userDisplay.secondaryText}
                        >
                          {userDisplay.secondaryText}
                        </Text>
                      ) : null}
                    </div>
                    <DownOutlined style={{ fontSize: 10, color: '#9ca3af', flexShrink: 0 }} />
                  </>
                )}
              </div>
            </Dropdown>
          </div>
        </Header>
        <Content style={{ padding: isMobile ? 12 : 24, overflow: 'auto', background: '#f8f9fb', minWidth: 0 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
