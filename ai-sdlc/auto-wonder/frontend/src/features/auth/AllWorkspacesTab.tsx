import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
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
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { refreshTenantScopedQueries } from '@/features/workitem/queryCache';
import { useAllWorkspaces, useCancelAccessRequest } from './workspaceDiscoveryApi';
import { AccessRequestModal } from './AccessRequestModal';
import { WorkspaceEditModal } from './WorkspaceEditModal';
import { WorkspaceDeleteModal } from './WorkspaceDeleteModal';
import './workspaceLifecycle.css';

const { Text } = Typography;

const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;

const ACCENT_COLOR = 'var(--aw-accent-text)';
const BORDER_COLOR = 'var(--aw-border)';
const WORKSPACE_CARD_SHADOW = '0 0 0 1px var(--aw-accent-text)';

export function AllWorkspacesTab() {
  const { t } = useTranslation('auth');
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
      message.info(i18n.t('auth:workspace.membershipChanged'));
    }
  }, [targetChangedStatus, t]);

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
      message.info(i18n.t('auth:workspace.requestChanged'));
      return;
    }
    try {
      await cancelMutateAsync({ workspaceId: cancelTarget.id, requestId });
      message.success(i18n.t('auth:workspace.requestCancelled'));
      setCancelTargetId(null);
    } catch (e) {
      // Business errors (无权限/状态已变化/记录不存在) surface via ApiError.message.
      message.error(e instanceof ApiError ? e.message : i18n.t('auth:workspace.cancelFailed'));
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
          aria-label={t('workspace.search')}
          placeholder={t('workspace.searchPlaceholder')}
          allowClear
          value={keyword}
          onChange={(event) => handleKeywordChange(event.target.value)}
          style={{ maxWidth: 320 }}
        />
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: 'var(--aw-muted)' }}>{t('workspace.count', { count: total })}</Text>
          {refetching && <LoadingOutlined data-testid="all-workspaces-refetching" style={{ color: ACCENT_COLOR }} />}
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
              ? t('workspace.noMatches', { keyword: debouncedKeyword.trim() })
              : t('workspace.empty')}
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
        title={t('workspace.withdraw')}
        open={cancelTarget !== null}
        okText={t('workspace.confirmWithdraw')}
        cancelText={t('action.back')}
        okButtonProps={{ danger: true }}
        confirmLoading={cancelling}
        onCancel={() => setCancelTargetId(null)}
        onOk={handleCancelRequest}
        destroyOnHidden
      >
        <Text>
          {t('workspace.withdrawDescription', { name: cancelTarget?.name })}
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
  const { t } = useTranslation('auth');
  const isMember = workspace.membershipStatus === 'MEMBER';
  // canManage comes from the server (owner, workspace ADMIN, or platform admin), so a
  // platform admin that never joined this workspace still gets enter/edit/delete entry
  // points here while the backend enforces the same rule independently.
  const canManage = workspace.canManage === true;
  const enterable = isMember || canManage;
  const testId = `all-workspace-card-${workspace.id}`;
  // Hover highlight lives on the card shell (same as the "my workspaces" tab), not on the
  // inner enter button, so the accent frame hugs the card's own border and covers the
  // manage buttons area too.
  const cardHoverProps = {
    onMouseEnter: (event: React.MouseEvent<HTMLDivElement>) => {
      event.currentTarget.style.borderColor = ACCENT_COLOR;
      event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
      event.currentTarget.style.transform = 'translateY(-1px)';
    },
    onMouseLeave: (event: React.MouseEvent<HTMLDivElement>) => {
      event.currentTarget.style.borderColor = 'var(--aw-border)';
      event.currentTarget.style.boxShadow = 'none';
      event.currentTarget.style.transform = 'none';
    },
    onFocus: (event: React.FocusEvent<HTMLDivElement>) => {
      event.currentTarget.style.borderColor = ACCENT_COLOR;
      event.currentTarget.style.boxShadow = WORKSPACE_CARD_SHADOW;
    },
    onBlur: (event: React.FocusEvent<HTMLDivElement>) => {
      event.currentTarget.style.borderColor = 'var(--aw-border)';
      event.currentTarget.style.boxShadow = 'none';
    },
  };

  const description = (
    <>
      {workspace.membershipStatus === 'PENDING' && <span style={pendingBadgeStyle}>{t('workspace.pending')}</span>}
      {workspace.membershipStatus === 'NOT_MEMBER' && <span style={notMemberBadgeStyle}>{t('workspace.notMember')}</span>}
      <span style={markStyle}>{getWorkspaceInitial(workspace.name)}</span>
      <EllipsisText tooltip={workspace.name} style={nameStyle}>{workspace.name}</EllipsisText>
      <EllipsisText tooltip={workspace.description || t('workspace.noDescription')} lines={2} style={descStyle}>{workspace.description || t('workspace.noDescription')}</EllipsisText>
    </>
  );
  // F7.5: the fade covers the descriptive half only, and only for cards the user cannot
  // enter — a platform admin's card keeps full contrast because its actions are real.
  const body = enterable ? description : <div style={fadedBodyStyle}>{description}</div>;

  if (enterable) {
    return (
      <div data-testid={testId} style={cardStyle} {...cardHoverProps}>
        <button
          type="button"
          data-testid={`all-workspace-enter-${workspace.id}`}
          style={enterButtonStyle}
          // Membership is the entire point of this screen, but for MEMBER cards it is
          // otherwise carried only by badge *absence* and opacity — both purely visual.
          // Folding 已加入 into the accessible name is what makes status non-visual.
          aria-label={switching
            ? t(isMember ? 'workspace.enteringMember' : 'workspace.enteringName', { name: workspace.name })
            : t(isMember ? 'workspace.enterMember' : 'workspace.enterName', { name: workspace.name })}
          aria-busy={switching}
          // Blocked by any in-flight switch, not just this card's: two racing successful
          // switches would pair one workspace's token with another's currentWorkspace.
          disabled={switchBlocked}
          onClick={() => onEnter(workspace)}
        >
          {body}
          <span style={actionStyle}>
            {switching ? (
              <>{t('workspace.entering')}<LoadingOutlined />
              </>
            ) : (
              <>{t('workspace.enter')}<ArrowRightOutlined />
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
              aria-label={t('workspace.editName', { name: workspace.name })}
              onClick={() => onEdit(workspace)}
            >
              <EditOutlined />
            </button>
            <button
              type="button"
              className="aw-card-manage-button aw-card-manage-button--danger"
              data-testid={`all-workspace-delete-${workspace.id}`}
              aria-label={t('workspace.deleteName', { name: workspace.name })}
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
    <div data-testid={testId} style={cardStyle} {...cardHoverProps}>
      {body}
      {workspace.membershipStatus === 'NOT_MEMBER' ? (
        // F7.4: the non-member card is a <div>, not a button, so there is no enter handler here
        // for this click to reach — the isolation is structural rather than a stopPropagation.
        <button
          type="button"
          className="aw-apply-access-button"
          data-testid={`apply-access-${workspace.id}`}
          onClick={() => onApply(workspace)}
        >{t('workspace.requestAccess')}</button>
      ) : (
        <div style={pendingActionStyle}>
          <span style={{ color: 'var(--aw-muted)' }}>{t('workspace.requestPending')}</span>
          <button
            type="button"
            style={cancelButtonStyle}
            onClick={() => onCancelRequest(workspace)}
          >{t('workspace.withdraw')}</button>
        </div>
      )}
    </div>
  );
}

function getWorkspaceInitial(name: string) {
  return name.trim().slice(0, 2).toUpperCase() || i18n.t('auth:workspace.emptyName');
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
  minWidth: 0,
  position: 'relative',
  display: 'flex',
  flexDirection: 'column',
  minHeight: 178,
  border: '1px solid var(--aw-border)',
  background: 'var(--aw-panel)',
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
  background: 'var(--aw-primary)',
  color: 'var(--aw-primary-ink)',
  fontWeight: 800,
  fontSize: 16,
};

const nameStyle: CSSProperties = {
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

const descStyle: CSSProperties = {
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

const actionStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  marginTop: 18,
  color: ACCENT_COLOR,
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
  border: '1px solid var(--aw-error)',
  background: 'var(--aw-panel)',
  color: 'var(--aw-error)',
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
  background: 'var(--aw-raised)',
  border: `1px solid ${BORDER_COLOR}`,
  color: ACCENT_COLOR,
};

const notMemberBadgeStyle: CSSProperties = {
  ...badgeBaseStyle,
  background: 'var(--aw-raised)',
  border: '1px solid var(--aw-border)',
  color: 'var(--aw-muted)',
};

const emptyStateStyle: CSSProperties = {
  border: `1px dashed ${BORDER_COLOR}`,
  background: 'var(--aw-panel)',
  borderRadius: 8,
  padding: 32,
};
