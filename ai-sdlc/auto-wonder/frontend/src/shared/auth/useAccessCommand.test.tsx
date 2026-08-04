import { act, renderHook } from '@testing-library/react';
import { message } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAccessCommand } from './useAccessCommand';
import { useAuthStore } from './store';

describe('useAccessCommand', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('reports a clear error and does not invoke a denied command', () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const command = vi.fn();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    const { result } = renderHook(() => useAccessCommand());

    act(() => result.current('READ_WRITE', '编辑工单', command));

    expect(error).toHaveBeenCalledWith('当前为只读权限，编辑工单需要读写权限');
    expect(command).not.toHaveBeenCalled();
  });

  it('reports both the current level and required admin level', () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const command = vi.fn();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    const { result } = renderHook(() => useAccessCommand());

    act(() => result.current('ADMIN', '删除执行器', command));

    expect(error).toHaveBeenCalledWith('当前为读写权限，删除执行器需要管理员权限');
    expect(command).not.toHaveBeenCalled();
  });

  it('invokes an allowed command exactly once and returns its result', () => {
    const command = vi.fn(() => 'done');
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'ADMIN');
    const { result } = renderHook(() => useAccessCommand());
    let commandResult: string | undefined;

    act(() => {
      commandResult = result.current('READ_WRITE', '编辑工单', command);
    });

    expect(command).toHaveBeenCalledTimes(1);
    expect(commandResult).toBe('done');
  });

  it('rechecks the latest store level when an existing callback is invoked', () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    const command = vi.fn();
    useAuthStore.getState().setCurrentOrg({ id: 1, name: 'O', description: '' }, 'ADMIN');
    const { result } = renderHook(() => useAccessCommand());
    const existingCallback = result.current;

    act(() => {
      useAuthStore.getState().setCurrentOrg(
        { id: 1, name: 'O', description: '' },
        'READ_ONLY',
      );
      existingCallback('ADMIN', '编辑成员', command);
    });

    expect(error).toHaveBeenCalledWith('当前为只读权限，编辑成员需要管理员权限');
    expect(command).not.toHaveBeenCalled();
  });
});
