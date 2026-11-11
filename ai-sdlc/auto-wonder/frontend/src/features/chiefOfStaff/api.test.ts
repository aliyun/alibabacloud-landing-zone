import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/client';
import { continueInitialization, initializationContinuation, type ActionPlan } from './api';

vi.mock('@/shared/api/client', () => ({ apiClient: { post: vi.fn() } }));
const plan = (tool: string, result: unknown): ActionPlan => ({ id: 'plan-1', turnId: 200, actionsJson: '[]', payloadHash: 'hash', status: 'COMPLETED', resultJson: JSON.stringify([{ tool, result }]), expiresAt: '' });
beforeEach(() => { vi.clearAllMocks(); });

describe('initializationContinuation', () => {
  it.each(['initialize_workspace', 'resume_workspace_initialization'])('continues %s using the same run and actual launch commands', tool => {
    const content = initializationContinuation(plan(`autowonder.${tool}`, { runId: 42, status: 'SUCCEEDED' }));
    expect(content).toContain('autowonder.get_workspace_initialization');
    expect(content).toContain('runId=42');
    expect(content).toContain('SUCCEEDED');
    expect(content).toContain('autowonder.build_executor_launch_command');
    expect(content).toContain('不要重复初始化');
  });
  it.each(['FAILED', 'RUNNING', 'UNKNOWN', undefined])('queries %s without claiming completion or automatically writing again', status => {
    const content = initializationContinuation(plan('autowonder.initialize_workspace', { runId: '42', status }));
    expect(content).toContain('runId=42');
    expect(content).toContain('只有');
    expect(content).toContain('不得声称初始化完成');
    expect(content).toContain('不得自动重试或恢复初始化');
    expect(content).not.toContain('模板已导入');
  });
  it.each([undefined, null, {}, [], true, 0, -1, 1.2, '42 injected', ''])('ignores invalid runId %j', runId => {
    expect(initializationContinuation(plan('autowonder.initialize_workspace', { runId, status: 'SUCCEEDED' }))).toBeNull();
  });
  it.each(['null', '{}', '[null]', '{', '[]'])('ignores malformed results %s', resultJson => {
    expect(initializationContinuation({ ...plan('other', {}), resultJson })).toBeNull();
  });
  it.each(['initialize_harness', 'resume_harness_initialization'])('preserves legacy %s behavior', tool => {
    expect(initializationContinuation(plan(`autowonder.${tool}`, { onboardingId: 23, status: 'SUCCEEDED' }))).toBe('[AutoWonder 初始化续接 plan-1] 模板已导入。请读取引导记录 23，继续项目配置并直接展示下一步。不要重复导入模板。按此前对话语言回复。');
    expect(initializationContinuation(plan(`autowonder.${tool}`, { onboardingId: 23, status: 'FAILED' }))).toBeNull();
  });
  it('queries a failed plan with a valid workspace run without repeating initialization', () => {
    expect(initializationContinuation({ ...plan('autowonder.initialize_workspace', { runId: 42, status: 'FAILED' }), status: 'FAILED' })).toContain('runId=42');
    expect(initializationContinuation({ ...plan('autowonder.initialize_harness', { onboardingId: 42, status: 'SUCCEEDED' }), status: 'FAILED' })).toBeNull();
  });
  it('ignores unrelated tools and unsettled plans', () => {
    expect(initializationContinuation(plan('autowonder.get_workspace_initialization', { runId: 42, status: 'SUCCEEDED' }))).toBeNull();
    for (const status of ['PENDING', 'EXECUTING', 'CANCELED']) {
      expect(initializationContinuation({ ...plan('autowonder.initialize_workspace', { runId: 42 }), status })).toBeNull();
    }
  });
  it('preserves first-send and failed-turn idempotency keys', async () => {
    await continueInitialization(100, 'plan-1', 'next');
    await continueInitialization(100, 'plan-1', 'next', 9);
    expect(apiClient.post).toHaveBeenNthCalledWith(1, '/api/platform/conversations/100/turns', { content: 'next', clientMessageId: 'initialization-continuation:plan-1:first', pagePath: null, imageIds: undefined });
    expect(apiClient.post).toHaveBeenNthCalledWith(2, '/api/platform/conversations/100/turns', { content: 'next', clientMessageId: 'initialization-continuation:plan-1:9', pagePath: null, imageIds: undefined });
  });
});
