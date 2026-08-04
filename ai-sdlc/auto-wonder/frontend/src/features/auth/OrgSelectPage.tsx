import { useState, type CSSProperties } from 'react';
import { Button, Card, Form, Input, Typography, message, Spin } from 'antd';
import { ArrowRightOutlined, BgColorsOutlined, PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import type { OrgInfo, SwitchOrgResponse } from '@/shared/types/common';
import { ApiError } from '@/shared/types/common';
import { refreshWorkitemTenantScopedQueries } from '@/features/workitem/queryCache';
import {
  BRANDING_QUERY_KEY,
  DEFAULT_BRANDING,
  getPublicBranding,
} from '@/features/platform/brandingApi';

const { Title, Text } = Typography;
const { TextArea } = Input;

const BRAND_ORANGE = '#ff6a00';
const BRAND_ORANGE_DARK = '#ea580c';
const BRAND_ORANGE_LINE = '#fed7aa';
const ORG_CARD_SHADOW = '0 0 0 2px rgba(255, 106, 0, 0.08), 0 14px 28px rgba(255, 106, 0, 0.12)';

export function OrgSelectPage() {
  const [creating, setCreating] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentOrg = useAuthStore((s) => s.setCurrentOrg);
  const currentOrg = useAuthStore((s) => s.currentOrg);
  const [form] = Form.useForm();
  const { data: publicBranding = DEFAULT_BRANDING } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const { data: orgs, isLoading, refetch } = useQuery({
    queryKey: ['orgs', 'mine'],
    queryFn: async () => {
      const resp = await apiClient.get<OrgInfo[]>('/api/orgs/mine');
      return resp.data;
    },
  });

  const handleSwitch = async (org: OrgInfo) => {
    try {
      const resp = await apiClient.post<SwitchOrgResponse>(`/api/orgs/${org.id}/switch`);
      const { accessToken, accessLevel } = resp.data;
      setAccessToken(accessToken);
      setCurrentOrg(org, accessLevel);
      refreshWorkitemTenantScopedQueries(queryClient);
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
      const resp = await apiClient.post<OrgInfo>('/api/orgs', values);
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

  return (
    <div style={pageShellStyle}>
      <div style={contentStyle}>
        <div style={headerStyle}>
          <div>
            <Title level={2} style={{ margin: 0, color: '#111827', letterSpacing: 0 }}>选择组织</Title>
            <Text style={{ display: 'block', marginTop: 8, color: '#697386' }}>请选择要进入的 {publicBranding.platformName} 组织工作空间</Text>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            <Button
              icon={<BgColorsOutlined />}
              onClick={() => navigate('/orgs/branding')}
            >
              品牌配置
            </Button>
            <div style={userPanelStyle}>
              <Text strong style={{ display: 'block', color: '#111827' }}>{currentOrg?.name || '未选择组织'}</Text>
              <Text style={{ color: '#697386', fontSize: 12 }}>当前工作空间</Text>
            </div>
          </div>
        </div>

        {isLoading ? (
          <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : orgs && orgs.length > 0 ? (
          <div data-testid="org-select-grid" style={orgGridStyle}>
            {orgs.map((org) => {
              const active = currentOrg?.id === org.id;
              return (
                <button
                  key={org.id}
                  type="button"
                  data-testid={`org-card-${org.id}`}
                  style={getOrgCardStyle(active)}
                  aria-label={`进入组织 ${org.name}`}
                  onMouseEnter={(event) => {
                    event.currentTarget.style.borderColor = BRAND_ORANGE;
                    event.currentTarget.style.boxShadow = ORG_CARD_SHADOW;
                    event.currentTarget.style.transform = 'translateY(-1px)';
                  }}
                  onMouseLeave={(event) => {
                    const nextStyle = getOrgCardStyle(active);
                    event.currentTarget.style.borderColor = String(nextStyle.borderColor);
                    event.currentTarget.style.boxShadow = String(nextStyle.boxShadow);
                    event.currentTarget.style.transform = String(nextStyle.transform || 'none');
                  }}
                  onFocus={(event) => {
                    event.currentTarget.style.borderColor = BRAND_ORANGE;
                    event.currentTarget.style.boxShadow = ORG_CARD_SHADOW;
                  }}
                  onBlur={(event) => {
                    const nextStyle = getOrgCardStyle(active);
                    event.currentTarget.style.borderColor = String(nextStyle.borderColor);
                    event.currentTarget.style.boxShadow = String(nextStyle.boxShadow);
                  }}
                  onClick={() => handleSwitch(org)}
                >
                  {active && <span style={currentBadgeStyle}>当前</span>}
                  <span style={orgMarkStyle}>{getOrgInitial(org.name)}</span>
                  <span style={orgNameStyle}>{org.name}</span>
                  <span style={orgDescStyle}>{org.description || '暂无描述'}</span>
                  <span style={orgActionStyle}>
                    进入组织 <ArrowRightOutlined />
                  </span>
                </button>
              );
            })}

            {!showCreateForm && (
              <button
                type="button"
                data-testid="org-create-card"
                style={createCardStyle}
                onClick={() => setShowCreateForm(true)}
              >
                <span style={plusMarkStyle}><PlusOutlined /></span>
                <span style={orgNameStyle}>创建新组织</span>
                <span style={{ ...orgDescStyle, textAlign: 'center' }}>初始化新的组织工作空间</span>
              </button>
            )}
          </div>
        ) : (
          <div data-testid="org-select-grid" style={orgGridStyle}>
            <div style={emptyStateStyle}>
              <Text type="secondary">暂无已加入的组织，请创建一个</Text>
            </div>
            {!showCreateForm && (
              <button type="button" data-testid="org-create-card" style={createCardStyle} onClick={() => setShowCreateForm(true)}>
                <span style={plusMarkStyle}><PlusOutlined /></span>
                <span style={orgNameStyle}>创建新组织</span>
                <span style={{ ...orgDescStyle, textAlign: 'center' }}>初始化新的组织工作空间</span>
              </button>
            )}
          </div>
        )}

        {showCreateForm && (
          <Card style={createFormCardStyle} styles={{ body: { padding: 18 } }}>
            <Form form={form} onFinish={handleCreate} layout="vertical">
              <Form.Item
                name="name"
                label="组织名称"
                rules={[{ required: true, message: '组织名称不能为空' }]}
              >
                <Input placeholder="输入组织名称" maxLength={128} />
              </Form.Item>
              <Form.Item name="description" label="组织描述">
                <Input placeholder="简要描述组织用途" maxLength={512} />
              </Form.Item>
              <Form.Item name="background" label="组织背景">
                <TextArea placeholder="组织的行业背景、技术栈、团队规模等信息" rows={3} />
              </Form.Item>
              <Form.Item>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={creating}
                  style={{ marginRight: 8, background: BRAND_ORANGE, borderColor: BRAND_ORANGE }}
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
      </div>
    </div>
  );
}

function getOrgInitial(name: string) {
  return name.trim().slice(0, 2).toUpperCase() || 'ORG';
}

function getOrgCardStyle(active: boolean): CSSProperties {
  return {
    ...orgCardStyle,
    borderColor: active ? BRAND_ORANGE : '#e5e7eb',
    boxShadow: active ? ORG_CARD_SHADOW : 'none',
  };
}

const pageShellStyle: CSSProperties = {
  minHeight: '100vh',
  padding: '42px 24px',
  background: 'radial-gradient(circle at 18% 0%, rgba(255, 106, 0, 0.12), transparent 28%), linear-gradient(180deg, #fff7ed 0%, #ffffff 34%, #ffffff 100%)',
};

const contentStyle: CSSProperties = {
  width: 'min(1120px, 100%)',
  margin: '0 auto',
};

const headerStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  justifyContent: 'space-between',
  gap: 24,
  marginBottom: 26,
  flexWrap: 'wrap',
};

const userPanelStyle: CSSProperties = {
  minWidth: 190,
  border: `1px solid ${BRAND_ORANGE_LINE}`,
  background: 'rgba(255,255,255,0.86)',
  borderRadius: 10,
  padding: '12px 14px',
  textAlign: 'right',
  boxShadow: '0 8px 24px rgba(255, 106, 0, 0.08)',
};

const orgGridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
  gap: 16,
};

const orgCardStyle: CSSProperties = {
  position: 'relative',
  minHeight: 178,
  border: '1px solid #e5e7eb',
  background: '#fff',
  borderRadius: 8,
  padding: 18,
  cursor: 'pointer',
  textAlign: 'left',
  appearance: 'none',
  transition: 'border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease',
};

const createCardStyle: CSSProperties = {
  ...orgCardStyle,
  borderStyle: 'dashed',
  borderColor: '#fdba74',
  background: 'rgba(255, 247, 237, 0.72)',
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
  background: `linear-gradient(135deg, ${BRAND_ORANGE}, #f59e0b)`,
  color: '#fff',
  fontWeight: 800,
  fontSize: 16,
};

const plusMarkStyle: CSSProperties = {
  width: 44,
  height: 44,
  borderRadius: 8,
  border: '1px solid #fdba74',
  color: BRAND_ORANGE,
  background: '#fff',
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
  background: BRAND_ORANGE,
  color: '#fff',
  fontSize: 12,
  fontWeight: 700,
};

const orgNameStyle: CSSProperties = {
  display: 'block',
  color: '#111827',
  fontSize: 18,
  fontWeight: 700,
  lineHeight: 1.3,
  marginBottom: 8,
};

const orgDescStyle: CSSProperties = {
  display: 'block',
  color: '#697386',
  fontSize: 13,
  lineHeight: 1.6,
  minHeight: 42,
};

const orgActionStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  marginTop: 18,
  color: BRAND_ORANGE_DARK,
  fontSize: 13,
  fontWeight: 700,
};

const emptyStateStyle: CSSProperties = {
  minHeight: 178,
  border: `1px dashed ${BRAND_ORANGE_LINE}`,
  background: '#fff',
  borderRadius: 8,
  display: 'grid',
  placeItems: 'center',
  padding: 18,
};

const createFormCardStyle: CSSProperties = {
  marginTop: 18,
  borderColor: BRAND_ORANGE_LINE,
  boxShadow: '0 10px 28px rgba(255, 106, 0, 0.08)',
};
