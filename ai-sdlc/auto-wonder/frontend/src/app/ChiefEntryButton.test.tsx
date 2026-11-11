import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupStatus } from '@/features/chiefOfStaff/api';
import { useAuthStore } from '@/shared/auth/store';
import { ChiefEntryButton } from './ChiefEntryButton';
import {
  publishGuideRuntime,
  resetWorkspaceGuideBridge,
  subscribeChiefOpenPanel,
  subscribeGuideEntryClick,
  type ChiefOpenRequest,
} from '@/features/workspaceGuide/bridge';

let opens: ChiefOpenRequest[] = [];
let entries = 0;

vi.mock('@/features/chiefOfStaff/api', () => ({ setupStatus: vi.fn() }));

beforeEach(() => {
  resetWorkspaceGuideBridge();
  opens = [];
  entries = 0;
  vi.mocked(setupStatus).mockReset();
  subscribeChiefOpenPanel(request => opens.push(request));
  subscribeGuideEntryClick(() => { entries += 1; });
});

afterEach(() => {
  resetWorkspaceGuideBridge();
  useAuthStore.getState().clear();
});

describe('ChiefEntryButton', () => {
  it.each([true, false])('opens standalone Chief only when the current workspace is ready (%s)', async (ready) => {
    useAuthStore.getState().setUser({ id: 7, username: 'user', nickname: '', email: '' });
    useAuthStore.getState().setCurrentWorkspace({ id: 4, name: 'Space', description: '' }, 'READ_WRITE');
    vi.mocked(setupStatus).mockResolvedValue({
      ready, agentId: 2, executorId: 3, agentConfigured: true, skillsConfigured: true,
      executorConfigured: true, launchConfigured: ready, missingSkills: [],
    });
    render(<ChiefEntryButton standalone />);

    fireEvent.click(screen.getByRole('button', { name: '打开 Chief' }));

    await waitFor(() => expect(setupStatus).toHaveBeenCalledOnce());
    if (ready) await waitFor(() => expect(opens).toHaveLength(1));
    else {
      expect(await screen.findByText('请进入工作空间，完成 Chief 配置并启动执行器。')).toBeVisible();
      expect(opens).toHaveLength(0);
    }
  });

  it('prompts users to enter a workspace before checking standalone Chief', async () => {
    render(<ChiefEntryButton standalone />);
    fireEvent.click(screen.getByRole('button', { name: '打开 Chief' }));
    expect(await screen.findByText('请先进入一个工作空间，再使用 Chief。')).toBeVisible();
    expect(setupStatus).not.toHaveBeenCalled();
    expect(opens).toHaveLength(0);
  });

  it('only opens Chief without contacting the guide outside phase 2', () => {
    render(<ChiefEntryButton />);

    const button = screen.getByRole('button', { name: '打开 Chief' });
    expect(button).not.toHaveClass('aw-guide-focus');
    fireEvent.click(button);

    expect(entries).toBe(0);
    expect(opens).toHaveLength(1);
    expect(opens[0]).toMatchObject({ expanded: false });
  });

  it('notifies the guide and forces expanded mode on the phase 2 entry', () => {
    publishGuideRuntime({ active: true, workspaceId: 1, stage: 2, substep: 0, capabilityReady: true });
    render(<ChiefEntryButton />);

    const button = screen.getByRole('button', { name: '打开 Chief' });
    expect(button).toHaveClass('aw-guide-focus');
    fireEvent.click(button);

    expect(entries).toBe(1);
    expect(opens).toHaveLength(1);
    expect(opens[0]).toMatchObject({ expanded: true });
  });
});
