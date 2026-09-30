import { useState, type CSSProperties } from 'react';
import { Button, Card, Form, Input, Typography, message, Spin, Tabs } from 'antd';
import {
  BgColorsOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  RestOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { PageHeader } from '@/shared/ui/PageHeader';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAuthStore } from '@/shared/auth/store';
import type { WorkspaceInfo, SwitchWorkspaceResponse } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { myWorkspacesQueryKey } from './api';
import { AllWorkspacesTab } from './AllWorkspacesTab';
import { WorkspaceEditModal } from './WorkspaceEditModal';
import { WorkspaceDeleteModal } from './WorkspaceDeleteModal';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';
import './workspaceLifecycle.css';

const { Title, Text } = Typography;
const { TextArea } = Input;

const ACCENT_COLOR = 'var(--aw-accent-text)';
const BORDER_COLOR = 'var(--aw-border)';
const WORKSPACE_CARD_SHADOW = '0 0 0 1px var(--aw-accent-text)';

export function WorkspaceSelectPage() {
  const [creating, setCreating] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editTarget, setEditTarget] = useState<WorkspaceInfo | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<WorkspaceInfo | null>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentWorkspace = useAuthStore((s) => s.setCurrentWorkspace);
  const clearCurrentWorkspace = useAuthStore((s) => s.clearCurrentWorkspace);
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const user = useAuthStore((s) => s.user);
  const [form] = Form.useForm();
  const { data: workspaces, isLoading, refetch } = useQuery({
    queryKey: myWorkspacesQueryKey(user?.id ?? null),
    queryFn: async () => {
      const resp = await apiClient.get<WorkspaceInfo[]>('/api/workspaces/mine');
      return resp.data;
    },
  });

  const handleSwitch = async (workspace: WorkspaceInfo) => {
    try {
      const resp = await apiClient.post<SwitchWorkspaceResponse>(`/api/workspaces/${workspace.id}/switch`);
      const { accessToken, accessLevel } = resp.data;
      setAccessToken(accessToken);
      setCurrentWorkspace(workspace, accessLevel);
      await refreshTenantScopedQueries(queryClient);
      navigate('/');
    } catch (e) {
      if (e instanceof ApiError) {
        message.error(e.message);
      }
    }
  };

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

  return (
    <div style={pageShellStyle}>
      <PageHeader brandTo="/workspaces" />
      <div style={{ padding: 24 }}>
        <div style={headerStyle}>
          <Title level={2} style={{ margin: 0, color: 'var(--aw-text)', letterSpacing: 0 }}>选择工作空间</Title>
          <Button icon={<BgColorsOutlined />} onClick={() => navigate('/workspaces/branding')}>平台配置</Button>
        </div>

        <Tabs
          defaultActiveKey="mine"
          tabBarExtraContent={
          <button
            type="button"
            className="aw-recycle-bin-entry"
            data-testid="workspace-recycle-bin-entry"
            aria-label="打开工作空间回收站"
            onClick={() => navigate('/workspaces/recycle-bin')}
          >
            <RestOutlined /> 工作空间回收站
          </button>
          }
          items={[
            {
              key: 'mine',
              label: '我的工作空间',
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
                            {active && <span style={currentBadgeStyle}>当前</span>}
                            <button
                              type="button"
                              data-testid={`workspace-card-${workspace.id}`}
                              style={cardEnterStyle}
                              aria-label={`进入工作空间 ${workspace.name}`}
                              onClick={() => handleSwitch(workspace)}
                            >
                              <span style={orgMarkStyle}>{getOrgInitial(workspace.name)}</span>
                              <EllipsisText tooltip={workspace.name} style={orgNameStyle}>{workspace.name}</EllipsisText>
                              <EllipsisText tooltip={workspace.description || '暂无描述'} lines={2} style={orgDescStyle}>{workspace.description || '暂无描述'}</EllipsisText>
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
                                  aria-label={`编辑工作空间 ${workspace.name}`}
                                  onClick={() => setEditTarget(workspace)}
                                >
                                  <EditOutlined />
                                </button>
                                <button
                                  type="button"
                                  className="aw-card-manage-button aw-card-manage-button--danger"
                                  data-testid={`delete-workspace-${workspace.id}`}
                                  aria-label={`删除工作空间 ${workspace.name}`}
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
                          <span style={orgNameStyle}>创建新工作空间</span>
                          <span style={{ ...orgDescStyle, textAlign: 'center' }}>初始化新的工作空间</span>
                        </button>
                      )}
                    </div>
                  ) : (
                    <div data-testid="workspace-select-grid" style={orgGridStyle}>
                      <div style={emptyStateStyle}>
                        <Text type="secondary">暂无已加入的工作空间，请创建一个</Text>
                      </div>
                      {!showCreateForm && (
                        <button type="button" data-testid="workspace-create-card" style={createCardStyle} onClick={() => setShowCreateForm(true)}>
                          <span style={plusMarkStyle}><PlusOutlined /></span>
                          <span style={orgNameStyle}>创建新工作空间</span>
                          <span style={{ ...orgDescStyle, textAlign: 'center' }}>初始化新的工作空间</span>
                        </button>
                      )}
                    </div>
                  )}

                  {showCreateForm && (
                    <Card style={createFormCardStyle} styles={{ body: { padding: 18 } }}>
                      <Form form={form} onFinish={handleCreate} layout="vertical">
                        <Form.Item
                          name="name"
                          label="工作空间名称"
                          rules={[{ required: true, message: '工作空间名称不能为空' }]}
                        >
                          <Input placeholder="输入工作空间名称" maxLength={128} />
                        </Form.Item>
                        <Form.Item name="description" label="工作空间描述">
                          <Input placeholder="简要描述工作空间用途" maxLength={512} />
                        </Form.Item>
                        <Form.Item name="background" label="工作空间背景">
                          <TextArea placeholder="工作空间的行业背景、技术栈、团队规模等信息" rows={3} />
                        </Form.Item>
                        <Form.Item>
                          <Button
                            type="primary"
                            htmlType="submit"
                            loading={creating}
                            style={{ marginRight: 8 }}
                          >
                            创建
                          </Button>
                          <Button onClick={() => { setShowCreateForm(false); form.resetFields(); }}>
                            取消
                          </Button>
                        </Form.Item>
                      </Form>
                    </Card>
                  )}
                </>
              ),
            },
            {
              key: 'all',
              label: '所有工作空间',
              children: <AllWorkspacesTab />,
            },
          ]}
        />

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
  return name.trim().slice(0, 2).toUpperCase() || 'WORKSPACE';
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
