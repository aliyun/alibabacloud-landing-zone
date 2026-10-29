import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ApiError } from '@/shared/types/common';
import { ImIdentityConflictAlert, workspaceChannelsUrl } from './ImIdentityConflictAlert';

function conflictError(data: unknown) {
  return new ApiError('10409', 'conflict', null, data);
}

describe('ImIdentityConflictAlert', () => {
  it('renders the required legacy-occupancy copy with visible workspace links', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [{ workspaceId: 7, workspaceName: '项目A' }],
      restricted: false,
    })} />);

    expect(screen.getByText('此机器人配置已经在项目空间中配置，请先删除原来的项目中的群聊机器人配置')).toBeInTheDocument();
    expect(screen.getByText('占用该配置的工作空间：')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /项目A/ });
    expect(link).toHaveAttribute('href', '/workspaces?targetWorkspaceId=7&returnTo=%2Fintegrations%3Ftab%3Dchannels');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('删除原绑定会中断其群聊服务。')).toBeInTheDocument();
  });

  it('marks unreadable occupants as restricted without exposing them', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [],
      restricted: true,
    })} />);

    expect(screen.getByText('另有你无权查看的空间占用该配置，请联系对应空间管理员处理。')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('explains a deleted or unavailable occupying workspace', () => {
    render(<ImIdentityConflictAlert error={conflictError({
      reason: 'LEGACY_BINDING_OCCUPIED',
      workspaces: [],
      restricted: false,
    })} />);

    expect(screen.getByText('占用该配置的空间已删除或入口不可用，请联系对应空间管理员处理。')).toBeInTheDocument();
  });

  it('separates notification/group-chat conflicts from legacy occupancy', () => {
    const { rerender } = render(<ImIdentityConflictAlert error={conflictError({
      reason: 'NOTIFY_GROUP_IDENTITY_CONFLICT',
      workspaces: [],
      restricted: false,
    })} />);
    expect(screen.getByText('通知机器人与群聊机器人必须使用不同的应用身份。')).toBeInTheDocument();
    expect(screen.queryByText('删除原绑定会中断其群聊服务。')).not.toBeInTheDocument();

    rerender(<ImIdentityConflictAlert error={conflictError({
      reason: 'SHARED_IDENTITY_OCCUPIED',
      workspaces: [],
      restricted: false,
    })} />);
    expect(screen.getByText('该应用身份已被平台群聊机器人使用，请使用其他应用身份。')).toBeInTheDocument();
  });

  it('renders nothing for errors without a structured reason', () => {
    const { container } = render(<ImIdentityConflictAlert error={new Error('plain failure')} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('builds a workspace-scoped deep link that performs the switch on the workspace page', () => {
    expect(workspaceChannelsUrl(42)).toBe(
      '/workspaces?targetWorkspaceId=42&returnTo=%2Fintegrations%3Ftab%3Dchannels',
    );
  });
});
