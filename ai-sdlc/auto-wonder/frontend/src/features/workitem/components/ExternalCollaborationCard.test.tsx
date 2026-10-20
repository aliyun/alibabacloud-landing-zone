import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { ExternalCollaborationCard } from './ExternalCollaborationCard';
import type { ExternalCollaboration } from '@/shared/types/workitem';

afterEach(async () => { cleanup(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

function collaboration(overrides: Partial<ExternalCollaboration> = {}): ExternalCollaboration {
  return {
    provider: 'AONE',
    externalProjectId: '2087214',
    externalWorkitemId: '86623162',
    externalUrl: 'https://project.aone.example/86623162',
    sourceStatusId: '1807',
    sourceStatusName: '已发布待需求方验收',
    sourceLifecycle: 'ACTIVE',
    reporter: null,
    businessOwner: null,
    principalRelations: [],
    lastSyncAt: '2026-10-12T08:00:00Z',
    syncStatus: 'HEALTHY',
    lastErrorCode: null,
    lastError: null,
    ...overrides,
  };
}

describe('ExternalCollaborationCard existing sync statuses', () => {
  it('localizes attention status without translating source diagnostics', async () => {
    render(<ExternalCollaborationCard collaboration={collaboration({
      syncStatus: 'ACTION_REQUIRED',
      lastErrorCode: 'SOURCE_UNAVAILABLE',
      lastError: '来源原始诊断',
    })} />);
    expect(screen.getByText('需要处理')).toBeInTheDocument();
    expect(screen.getByText('来源原始诊断')).toBeInTheDocument();
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText('Needs attention')).toBeInTheDocument();
    expect(screen.getByText('SOURCE_UNAVAILABLE')).toBeInTheDocument();
    expect(screen.getByText('来源原始诊断')).toBeInTheDocument();
  });

  it('does not render an alert for a healthy sync even when a stale error is still attached', () => {
    const view = render(<ExternalCollaborationCard collaboration={collaboration({
      syncStatus: 'HEALTHY',
      lastErrorCode: null,
      lastError: '本单执行中，上一次同步暂缓',
    })} />);

    expect(screen.getByText('同步正常')).toBeInTheDocument();
    expect(view.container.querySelector('.ant-alert')).toBeNull();
  });
});
