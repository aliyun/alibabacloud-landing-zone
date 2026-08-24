import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { useAuthStore } from '@/shared/auth/store';
import { EvolutionPage } from './EvolutionPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EvolutionPage />
    </QueryClientProvider>,
  );
}

function overview() {
  return {
    proposals: [
      {
        id: 100,
        assetType: 'MEMORY',
        assetId: null,
        triggerType: 'WORKER_DELTA',
        status: 'REPLAY_PASSED',
        rootEvidenceJson: '[{"sourceType":"WORKER_DELTA","sourceRef":"trace:1"}]',
        policyJson: '{"action":"PATCH","reasonCode":"repeated_context_failure"}',
        candidatePatchJson: '{"title":"Use pnpm","contentMd":"Use pnpm in this repo."}',
        replayJson: '{"verdict":"PASS"}',
        gmtCreate: '2026-07-24T10:00:00Z',
      },
      {
        id: 101,
        assetType: 'SKILL',
        assetId: 88,
        triggerType: 'WORKER_DELTA',
        status: 'TRIAL',
        rootEvidenceJson: '[{"sourceType":"WORKER_DELTA","sourceRef":"trace:2"}]',
        policyJson: '{"action":"PATCH","reasonCode":"candidate_trial"}',
        candidatePatchJson: '{"name":"repo-checkout-safety","description":"Load repo map before checkout."}',
        trialJson: '{"taskPatternKey":"repo-checkout","decision":"CONTINUE_TRIAL","baselineSnapshot":{"posteriorMean":0.4,"effectiveSampleSize":8},"candidatePosteriorMean":0.67,"candidateEffectiveSampleSize":3,"posteriorWinProbability":0.82,"posteriorLoseProbability":0.04,"expectedLift":0.27}',
        gmtCreate: '2026-07-24T10:02:00Z',
      },
    ],
    evidence: [
      {
        id: 201,
        assetType: 'SKILL',
        assetId: 88,
        posteriorType: 'UPLIFT',
        contextKey: 'skill:review',
        sourceType: 'REPLAY_RESULT',
        sourceRef: 'proposal:100:replay',
        outcome: 'POSITIVE',
        posteriorMean: 0.67,
        effectiveSampleSize: 3,
        gmtCreate: '2026-07-24T10:01:00Z',
      },
    ],
  };
}

describe('EvolutionPage', () => {
  beforeEach(() => {
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_WRITE');
    vi.restoreAllMocks();
  });

  it('renders lean evolution overview and agent releases replay-passed proposal', async () => {
    let releaseBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: {
          contextKey: 'multi_repo_refactor',
          limit: 20,
          cards: [
            {
              assetType: 'SKILL',
              assetId: 88,
              name: 'multi-repo-refactor-safety',
              category: 'CODING',
              triggerHint: 'load repo-map before editing',
              lazyLoadRef: '/api/skills/88',
              posteriorMean: 0.82,
              effectiveSampleSize: 12,
            },
          ],
        },
      })),
      http.post('/api/evolution/proposals/100/agent-release', async ({ request }) => {
        releaseBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { proposalId: 100, action: 'RELEASED', status: 'RELEASED' },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('自进化')).toBeInTheDocument();
    expect(await screen.findByText('Use pnpm')).toBeInTheDocument();
    expect(screen.getByText('MEMORY')).toBeInTheDocument();
    expect(screen.getByText('REPLAY_PASSED')).toBeInTheDocument();
    expect(screen.getAllByText('PATCH').length).toBeGreaterThan(0);
    expect(screen.getByText('Proposal Inbox (2)')).toBeInTheDocument();
    expect(screen.getByText('Audit Evidence (1)')).toBeInTheDocument();
    expect(screen.getByText('Asset Manifest (1)')).toBeInTheDocument();
    await user.click(screen.getByText('Asset Manifest (1)'));
    expect(await screen.findByText('multi-repo-refactor-safety')).toBeInTheDocument();
    expect(screen.getByText('/api/skills/88')).toBeInTheDocument();
    expect(screen.queryByText(/Gates/)).not.toBeInTheDocument();

    await user.click(screen.getByText('Proposal Inbox (2)'));
    await user.click(screen.getByRole('button', { name: /Agent 发布/ }));

    await waitFor(() => {
      expect(releaseBody).toMatchObject({ allowRelease: true });
    });
  });

  it('shows trial posterior and records trial evidence before deciding', async () => {
    let trialEvidenceBody: Record<string, unknown> | null = null;
    let decideCalled = false;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
      http.post('/api/evolution/proposals/101/trial/evidence', async ({ request }) => {
        trialEvidenceBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            proposalId: 101,
            decision: 'CONTINUE_TRIAL',
            proposalStatus: 'TRIAL',
            taskPatternKey: 'repo-checkout',
            candidatePosteriorMean: 0.71,
            candidateEffectiveSampleSize: 4,
          },
        });
      }),
      http.post('/api/evolution/proposals/101/trial/decide', () => {
        decideCalled = true;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: {
            proposalId: 101,
            decision: 'ADOPT',
            proposalStatus: 'APPROVED',
            taskPatternKey: 'repo-checkout',
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('repo-checkout-safety')).toBeInTheDocument();
    expect(screen.getByText('TRIAL')).toBeInTheDocument();
    expect(screen.getByText('repo-checkout')).toBeInTheDocument();
    expect(screen.getByText('CONTINUE_TRIAL')).toBeInTheDocument();
    expect(screen.getByText('baseline 0.40 / candidate 0.67 · N=3')).toBeInTheDocument();
    expect(screen.getByText('pWin 0.82 / pLose 0.04 · lift 0.27')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Trial Pass/ }));
    await waitFor(() => {
      expect(trialEvidenceBody).toMatchObject({ rawOutcome: 'PASS', sourceType: 'HUMAN_REVIEW' });
    });

    await user.click(screen.getByRole('button', { name: /^Decide$/ }));
    await waitFor(() => {
      expect(decideCalled).toBe(true);
    });
  });

  it('submits worker delta JSON through orchestrate endpoint', async () => {
    let orchestrateBody: Record<string, unknown> | null = null;
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
      http.post('/api/evolution/orchestrate', async ({ request }) => {
        orchestrateBody = await request.json() as Record<string, unknown>;
        return HttpResponse.json({
          success: true, code: '0', message: '', traceId: null,
          data: { action: 'PROPOSAL_CREATED', proposalId: 101, replayVerdict: 'PASS' },
        });
      }),
    );

    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /提交 Delta/ }));
    await user.clear(screen.getByLabelText('Delta JSON'));
    fireEvent.change(screen.getByLabelText('Delta JSON'), { target: { value: JSON.stringify({
      evidenceEvent: {
        assetType: 'MEMORY',
        assetId: 0,
        posteriorType: 'UPLIFT',
        contextKey: 'repo:auto-wonder',
        sourceType: 'WORKER_DELTA',
        sourceRef: 'trace:front',
        outcome: 'NEGATIVE',
      },
      candidateAssetType: 'MEMORY',
      draftDeltaJson: { patch: { title: 'Worker delta', contentMd: 'Delta content' } },
    }) } });
    await user.click(screen.getByRole('button', { name: /^提\s*交$/ }));

    await waitFor(() => {
      expect(orchestrateBody).toMatchObject({ candidateAssetType: 'MEMORY' });
    });
  });

  it('keeps delta submission visible but does not open it for a read-only member', async () => {
    const error = vi.spyOn(message, 'error').mockImplementation(
      () => undefined as unknown as ReturnType<typeof message.error>,
    );
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'READ_ONLY');
    server.use(
      http.get('/api/evolution/admin/overview', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null, data: overview(),
      })),
      http.get('/api/evolution/admin/asset-manifest', () => HttpResponse.json({
        success: true, code: '0', message: '', traceId: null,
        data: { contextKey: null, limit: 20, cards: [] },
      })),
    );
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /提交 Delta/ }));

    expect(error).toHaveBeenCalledWith('当前为只读权限，提交进化 Delta需要读写权限');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
