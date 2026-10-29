import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { GroupBindingDetailDrawer, type GroupBindingDetailModel } from './GroupBindingDetailDrawer';

function model(overrides: Partial<GroupBindingDetailModel> = {}): GroupBindingDetailModel {
  return {
    bindingMode: 'SHARED',
    groupId: 'cid_full_group_identity_value_that_is_long',
    agentLabel: '项目管理员',
    robotIdentity: 'ding_shared_app',
    transportLabel: 'Stream',
    enabled: true,
    diagnostics: [{ label: '健康', value: '平台统一管理' }],
    ...overrides,
  };
}

describe('GroupBindingDetailDrawer', () => {
  it('shows the complete group id, technical fields and no secret section', () => {
    render(
      <GroupBindingDetailDrawer
        open
        model={model()}
        onClose={() => undefined}
        onEdit={() => undefined}
        onDelete={() => undefined}
      />,
    );

    const drawer = within(screen.getByRole('dialog', { name: '绑定详情' }));
    expect(drawer.getByText('cid_full_group_identity_value_that_is_long')).toBeInTheDocument();
    // Group confirmation was removed: the read-only details no longer describe it.
    expect(drawer.queryByText('群确认')).not.toBeInTheDocument();
    expect(drawer.getByText('ding_shared_app')).toBeInTheDocument();
    expect(drawer.getByText('项目管理员')).toBeInTheDocument();
    expect(drawer.getByText('Stream')).toBeInTheDocument();
    expect(drawer.getByText(/平台统一管理/)).toBeInTheDocument();
    expect(drawer.getByText('详情不展示任何密钥。')).toBeInTheDocument();
  });

  it('shows legacy rows without a fabricated group id or confirmation action', () => {
    render(
      <GroupBindingDetailDrawer
        open
        model={model({ bindingMode: 'LEGACY', groupId: null })}
        onClose={() => undefined}
        onEdit={() => undefined}
        onDelete={() => undefined}
      />,
    );
    const legacy = within(screen.getByRole('dialog', { name: '绑定详情' }));
    expect(legacy.getByText('无群 ID（按应用身份接入）')).toBeInTheDocument();
    expect(legacy.queryByText('无需确认')).not.toBeInTheDocument();
    expect(legacy.queryByRole('button', { name: '继续确认' })).not.toBeInTheDocument();
  });

  it('confirms deletion inside the read-only details before invoking it', async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    render(
      <GroupBindingDetailDrawer
        open
        model={model()}
        onClose={() => undefined}
        onEdit={() => undefined}
        onDelete={onDelete}
      />,
    );

    await user.click(screen.getByRole('button', { name: '删除绑定' }));
    expect(onDelete).not.toHaveBeenCalled();
    const confirm = within(await screen.findByRole('tooltip'));
    await user.click(confirm.getByRole('button', { name: '删除绑定' }));
    expect(onDelete).toHaveBeenCalledOnce();
  });
});
