import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Empty, Input, Modal, Pagination, Spin, Typography, message } from 'antd';
import { ArrowRightOutlined, DeleteOutlined, EditOutlined, LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/client';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import type { SwitchWorkspaceResponse, WorkspaceInfo, WorkspaceListItem } from '@/shared/types/common';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';
import { useAllWorkspaces, useCancelAccessRequest } from './workspaceDiscoveryApi';
import { AccessRequestModal } from './AccessRequestModal';
import { WorkspaceEditModal } from './WorkspaceEditModal';
import { WorkspaceDeleteModal } from './WorkspaceDeleteModal';
import './workspaceLifecycle.css';

const { Text } = Typography;

const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;

const BRAND_ORANGE = '#ff6a00';
const BRAND_ORANGE_DARK = '#ea580c';
const BRAND_ORANGE_LINE = '#fed7aa';
const WORKSPACE_CARD_SHADOW = '0 0 0 2px rgba(255, 106, 0, 0.08), 0 14px 28px rgba(255, 106, 0, 0.12)';

export function AllWorkspacesTab() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  // Only the id is kept, never a snapshot of the item: the live row is re-derived from
  // the current list on every render so an out-of-band refetch cannot leave the modal
  // submitting against a workspace whose membershipStatus has since changed.
  const [modalTargetId, setModalTargetId] = useState<number | null>(null);
  // The id of the switch currently in flight, or null when idle. This is state rather
  // than a ref because it has to render: the activated card must read as busy and the
  // other MEMBER cards must visibly stop being activatable.
  const [switchingId, setSwitchingId] = useState<number | null>(null);
  const debouncedKeyword = useDebouncedValue(keyword, KEYWORD_DEBOUNCE_MS);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const setCurrentWorkspace = useAuthStore((s) => s.setCurrentWorkspace);
  const clearCurrentWorkspace = useAuthStore((s) => s.clearCurrentWorkspace);
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);
  const switchingRef = useRef<number | null>(null);
  const lastModalTargetRef = useRef<WorkspaceListItem | null>(null);
  // Platform-admin manage entry points on the discovery cards; the modals are shared with the
  // "my workspaces" tab and the buttons only render when the server said canManage.
  const [editTarget, setEditTarget] = useState<WorkspaceInfo | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<WorkspaceInfo | null>(null);

  const { data, isLoading, isFetching } = useAllWorkspaces(
    debouncedKeyword,
    page,
    PAGE_SIZE,
  );

  const workspaces = data?.list ?? [];
  const total = data?.total ?? 0;
  // isLoading is only true when there is no data at all to show. Once placeholderData
  // hands back the previous page, the grid must stay mounted, so a refetch is signalled
  // in place rather than by swapping the list for a spinner.
  const showFirstLoad = isLoading && !data;
  // isPlaceholderData already implies data exists, so this reduces to "a fetch is in
  // flight while rows are still on screen" — the in-place refetch hint.
  const refetching = isFetching && Boolean(data);

  // Derived, never snapshotted. The live row is looked up on every render so an
  // out-of-band refetch cannot leave the modal submitting against a workspace whose
  // membershipStatus the server has already moved past.
  const liveModalTarget = modalTargetId === null
    ? null
    : workspaces.find((item) => item.id === modalTargetId) ?? null;
  // Two different situations, deliberately handled differently:
  //  - still listed but no longer NOT_MEMBER  -> the request is now impossible, close.
  //  - not on the current page at all         -> almost always just a keyword narrowing
  //    or page change while the dialog is open, which says nothing about membership.
  const targetChangedStatus = liveModalTarget !== null
    && liveModalTarget.membershipStatus !== 'NOT_MEMBER';

  // Last seen live row for the open modal. The live lookup always wins when the row is
  // on the current page, so a status change is never missed; this only keeps the dialog
  // mounted (with a real id and name) when the row is merely off the current page.
  if (liveModalTarget !== null && liveModalTarget.id === modalTargetId) {
    lastModalTargetRef.current = liveModalTarget;
  }
  if (modalTargetId === null) {
    lastModalTargetRef.current = null;
  }
  const modalTarget = liveModalTarget
    ?? (lastModalTargetRef.current?.id === modalTargetId ? lastModalTargetRef.current : null);

  // Closing beats leaving the dialog open with a disabled button: the only action it
  // offers has become impossible, so staying open is a dead end the user must diagnose.
  // The toast is what stops the close from being silent — a dialog vanishing
  // mid-interaction otherwise reads as a bug rather than as new information.
  useEffect(() => {
    if (targetChangedStatus) {
      setModalTargetId(null);
      message.info('该工作空间的加入状态已更新，请重新确认');
    }
  }, [targetChangedStatus]);

  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    // Without this a user on page 3 who narrows the keyword lands on an empty page.
    setPage(1);
  };

  const [cancelTargetId, setCancelTargetId] = useState<number | null>(null);
  const { mutateAsync: cancelMutateAsync, isPending: cancelling } = useCancelAccessRequest();

  // Derived from the live list exactly like the apply modal target: if the request was
  // reviewed out-of-band the row is no longer PENDING (or is gone), and the dialog must
  // not submit a cancel against a stale snapshot.
  const cancelTarget = cancelTargetId === null
    ? null
    : workspaces.find((item) => item.id === cancelTargetId) ?? null;

  const handleCancelRequest = async () => {
    const requestId = cancelTarget?.pendingRequestId;
    if (!cancelTarget || requestId == null) {
      setCancelTargetId(null);
      message.info('该申请的状态已更新，请重新确认');
      return;
    }
    try {
      await cancelMutateAsync({ workspaceId: cancelTarget.id, requestId });
      message.success('申请已撤销，可随时再次申请');
      setCancelTargetId(null);
    } catch (e) {
      // Business errors (无权限/状态已变化/记录不存在) surface via ApiError.message.
      message.error(e instanceof ApiError ? e.message : '撤销失败，请稍后重试');
    }
  };

  const handleSwitch = async (workspace: WorkspaceListItem) => {
    // The ref, not the state, is the authoritative gate: two clicks dispatched within
    // one React batch would both read the same stale `switchingId`, so a state-only
    // check still lets a second POST through. The ref is written synchronously.
    if (switchingRef.current !== null) return;
    switchingRef.current = workspace.id;
    setSwitchingId(workspace.id);
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
    } finally {
      // Cleared on both paths: a failed switch must not wedge the grid permanently.
      switchingRef.current = null;
      setSwitchingId(null);
    }
  };

  const handleDeleted = (workspace: WorkspaceInfo) => {
    // Same rule as the "my workspaces" tab: the access token stays bound to a workspace that
    // AuthFilter rejects after the delete, so the binding is dropped immediately.
    if (currentWorkspace?.id === workspace.id) {
      clearCurrentWorkspace();
    }
  };

  return (
    <div>
      <div style={toolbarStyle}>
        <Input
          aria-label="搜索工作空间"
          placeholder="搜索工作空间名称或描述"
          allowClear
          value={keyword}
          onChange={(event) => handleKeywordChange(event.target.value)}
          style={{ maxWidth: 320 }}
        />
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: '#697386' }}>共 {total} 个工作空间</Text>
          {refetching && <LoadingOutlined data-testid="all-workspaces-refetching" style={{ color: BRAND_ORANGE }} />}
        </div>
      </div>

      {showFirstLoad ? (
        <div data-testid="all-workspaces-loading" style={{ textAlign: 'center', padding: 48 }}>
          <Spin />
        </div>
      ) : workspaces.length === 0 ? (
        <div data-testid="all-workspaces-empty" style={emptyStateStyle}>
          <Empty
            description={debouncedKeyword.trim()
              ? `没有匹配「${debouncedKeyword.trim()}」的工作空间`
              : '平台上还没有任何工作空间'}
          />
        </div>
      ) : (
        <div data-testid="all-workspaces-grid" style={gridStyle}>
          {workspaces.map((workspace) => (
            <WorkspaceDiscoveryCard
              key={workspace.id}
              workspace={workspace}
              switching={switchingId === workspace.id}
              switchBlocked={switchingId !== null}
              onEnter={handleSwitch}
              onApply={(item) => setModalTargetId(item.id)}
              onCancelRequest={(item) => setCancelTargetId(item.id)}
              onEdit={(item) => setEditTarget(item)}
              onDelete={(item) => setDeleteTarget(item)}
            />
          ))}
        </div>
      )}

      {total > PAGE_SIZE && (
        <div style={{ marginTop: 20, textAlign: 'right' }}>
          <Pagination
            current={page}
            pageSize={PAGE_SIZE}
            total={total}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      )}

      <AccessRequestModal
        workspace={modalTarget}
        onClose={() => setModalTargetId(null)}
      />

      <WorkspaceEditModal
        workspace={editTarget}
        onClose={() => setEditTarget(null)}
      />

      <WorkspaceDeleteModal
        workspace={deleteTarget}
        onDeleted={handleDeleted}
        onClose={() => setDeleteTarget(null)}
      />

      <Modal
        title="撤销申请"
        open={cancelTarget !== null}
        okText="确认撤销"
        cancelText="返回"
        okButtonProps={{ danger: true }}
        confirmLoading={cancelling}
        onCancel={() => setCancelTargetId(null)}
        onOk={handleCancelRequest}
        destroyOnHidden
      >
        <Text>
          确定撤销加入「{cancelTarget?.name}」的申请吗？撤销后申请记录将被删除，你可以随时再次申请。
        </Text>
      </Modal>
    </div>
  );
}

interface WorkspaceDiscoveryCardProps {
  workspace: WorkspaceListItem;
  /** This card's own switch is the one in flight. */
  switching: boolean;
  /** Some switch is in flight, possibly another card's. */
  switchBlocked: boolean;
  onEnter: (workspace: WorkspaceListItem) => void;
  onApply: (workspace: WorkspaceListItem) => void;
  onCancelRequest: (workspace: WorkspaceListItem) => void;
  onEdit: (workspace: WorkspaceListItem) => void;
  onDelete: (workspace: WorkspaceListItem) => void;
}

function WorkspaceDiscoveryCard({
  workspace,
  switching,
  switchBlocked,
  onEnter,
  onApply,
  onCancelRequest,
  onEdit,
  onDelete,
}: WorkspaceDiscoveryCardProps) {
  const isMember = workspace.membershipStatus === 'MEMBER';
  // canManage comes from the server (owner, workspace ADMIN, or platform admin), so a
  // platform admin that never joined this workspace still gets enter/edit/delete entry
  // points here while the backend enforces the same rule independently.
  const canManage = workspace.canManage === true;
  const enterable = isMember || canManage;
  const testId = `all-workspace-card-${workspace.id}`;

  const description = (
    <>
      {workspace.membershipStatus === 'PENDING' && <span style={pendingBadgeStyle}>审批中</span>}
      {workspace.membershipStatus === 'NOT_MEMBER' && <span style={notMemberBadgeStyle}>未加入</span>}
      <span style={markStyle}>{getWorkspaceInitial(workspace.name)}</span>
      <span style={nameStyle}>{workspace.name}</span>
      <span style={descStyle}>{workspace.description || '暂无描述'}</span>
    </>
  );
  // F7.5: the fade covers the descriptive half only, and only for cards the user cannot
  // enter — a platform admin's card keeps full contrast because its actions are real.
  const body = enterable ? description : <div style={fadedBodyStyle}>{description}</div>;

  if (enterable) {
    return (
      <div data-testid={testId} style={cardStyle}>
        <button
          type="button"
          data-testid={`all-workspace-enter-${workspace.id}`}
          style={enterButtonStyle}
          // Membership is the entire point of this screen, but for MEMBER cards it is
          // otherwise carried only by badge *absence* and opacity — both purely visual.
          // Folding 已加入 into the accessible name is what makes status non-visual.
          aria-label={switching
            ? `正在进入工作空间 ${workspace.name}${isMember ? '（已加入）' : ''}`
            : `进入工作空间 ${workspace.name}${isMember ? '（已加入）' : ''}`}
          aria-busy={switching}
          // Blocked by any in-flight switch, not just this card's: two racing successful
          // switches would pair one workspace's token with another's currentWorkspace.
          disabled={switchBlocked}
          onMouseEnter={(event) => {
            event.currentTarget.style.borderColor = BRAND_ORANGE;
            event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
            event.currentTarget.style.transform = 'translateY(-1px)';
          }}
          onMouseLeave={(event) => {
            event.currentTarget.style.borderColor = 'transparent';
            event.currentTarget.style.boxShadow = 'none';
            event.currentTarget.style.transform = 'none';
          }}
          onFocus={(event) => {
            event.currentTarget.style.borderColor = BRAND_ORANGE;
            event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
          }}
          onBlur={(event) => {
            event.currentTarget.style.borderColor = 'transparent';
            event.currentTarget.style.boxShadow = 'none';
          }}
          onClick={() => onEnter(workspace)}
        >
          {body}
          <span style={actionStyle}>
            {switching ? (
              <>
                正在进入 <LoadingOutlined />
              </>
            ) : (
              <>
                进入工作空间 <ArrowRightOutlined />
              </>
            )}
          </span>
        </button>
        {/* F1.2: siblings of the enter button rather than children of it. A control nested
            inside a <button> is invalid HTML and its click would reach the enter handler. */}
        {canManage && (
          <div style={cardManageAreaStyle} data-testid={`all-workspace-manage-area-${workspace.id}`}>
            <button
              type="button"
              className="aw-card-manage-button"
              data-testid={`all-workspace-edit-${workspace.id}`}
              aria-label={`编辑工作空间 ${workspace.name}`}
              onClick={() => onEdit(workspace)}
            >
              <EditOutlined />
            </button>
            <button
              type="button"
              className="aw-card-manage-button aw-card-manage-button--danger"
              data-testid={`all-workspace-delete-${workspace.id}`}
              aria-label={`删除工作空间 ${workspace.name}`}
              onClick={() => onDelete(workspace)}
            >
              <DeleteOutlined />
            </button>
          </div>
        )}
      </div>
    );
  }

  // Non-members without manage rights are not activatable: the only action is the apply
  // button below, so the card itself must not be exposed as a button to keyboard or
  // screen-reader users.
  return (
    <div data-testid={testId} style={cardStyle}>
      {body}
      {workspace.membershipStatus === 'NOT_MEMBER' ? (
        // F7.4: the non-member card is a <div>, not a button, so there is no enter handler here
        // for this click to reach — the isolation is structural rather than a stopPropagation.
        <button
          type="button"
          className="aw-apply-access-button"
          data-testid={`apply-access-${workspace.id}`}
          onClick={() => onApply(workspace)}
        >
          申请权限
        </button>
      ) : (
        <div style={pendingActionStyle}>
          <span style={{ color: '#9ca3af' }}>申请审批中</span>
          <button
            type="button"
            style={cancelButtonStyle}
            onClick={() => onCancelRequest(workspace)}
          >
            撤销申请
          </button>
        </div>
      )}
    </div>
  );
}

function getWorkspaceInitial(name: string) {
  return name.trim().slice(0, 2).toUpperCase() || 'WORKSPACE';
}

const cardManageAreaStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 12,
};

const enterButtonStyle: CSSProperties = {
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

const fadedBodyStyle: CSSProperties = {
  opacity: 0.62,
};

const toolbarStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 16,
  marginBottom: 18,
  flexWrap: 'wrap',
};

const gridStyle: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
  gap: 16,
};

const cardStyle: CSSProperties = {
  position: 'relative',
  display: 'flex',
  flexDirection: 'column',
  minHeight: 178,
  border: '1px solid #e5e7eb',
  background: '#fff',
  borderRadius: 8,
  padding: 18,
  textAlign: 'left',
  appearance: 'none',
  transition: 'border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease',
};

const markStyle: CSSProperties = {
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

const nameStyle: CSSProperties = {
  display: 'block',
  color: '#111827',
  fontSize: 18,
  fontWeight: 700,
  lineHeight: 1.3,
  marginBottom: 8,
};

const descStyle: CSSProperties = {
  display: 'block',
  color: '#697386',
  fontSize: 13,
  lineHeight: 1.6,
  minHeight: 42,
};

const actionStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  marginTop: 18,
  color: BRAND_ORANGE_DARK,
  fontSize: 13,
  fontWeight: 700,
};

const pendingActionStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  marginTop: 18,
  fontSize: 13,
};

const cancelButtonStyle: CSSProperties = {
  border: '1px solid #fecaca',
  background: '#fff',
  color: '#dc2626',
  borderRadius: 6,
  padding: '4px 12px',
  fontSize: 13,
  fontWeight: 700,
  cursor: 'pointer',
};

const badgeBaseStyle: CSSProperties = {
  position: 'absolute',
  top: 12,
  right: 12,
  padding: '3px 8px',
  borderRadius: 999,
  fontSize: 12,
  fontWeight: 700,
};

const pendingBadgeStyle: CSSProperties = {
  ...badgeBaseStyle,
  background: '#fff7ed',
  border: `1px solid ${BRAND_ORANGE_LINE}`,
  color: BRAND_ORANGE_DARK,
};

const notMemberBadgeStyle: CSSProperties = {
  ...badgeBaseStyle,
  background: '#f3f4f6',
  border: '1px solid #e5e7eb',
  color: '#6b7280',
};

const emptyStateStyle: CSSProperties = {
  border: `1px dashed ${BRAND_ORANGE_LINE}`,
  background: '#fff',
  borderRadius: 8,
  padding: 32,
};
