import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import type { ClarificationConversation } from '@/features/workitem/clarification/types';

/**
 * 进入澄清面板时会先解析「工单最近一次会话」（工单 57463）。
 *
 * MSW 的运行时 handler 后注册者优先，而 `:conversationId` 详情 handler 会把 `latest`
 * 也当成会话 id 匹配掉。因此这里的注册必须发生在测试自己的详情 handler **之后**
 * （调用点统一放在各测试文件的 render helper 里，紧跟 mockSquads / mockPanel）。
 */
export const LATEST_CLARIFICATION_PATH =
  '/api/workitems/:workitemId/clarification-conversations/latest';

export function latestConversationResponse(
  data: Partial<ClarificationConversation> | null,
) {
  return HttpResponse.json({ success: true, code: '0', message: '', traceId: null, data });
}

/** 默认「该工单没有会话」：既有用例的行为保持不变。 */
export function mockLatestClarificationConversation(
  data: Partial<ClarificationConversation> | null = null,
) {
  server.use(http.get(LATEST_CLARIFICATION_PATH, () => latestConversationResponse(data)));
}

/** 最近会话查询失败：前端必须静默降级，不弹错误。 */
export function failLatestClarificationConversation(status = 500) {
  server.use(http.get(LATEST_CLARIFICATION_PATH, () => HttpResponse.json(
    { success: false, code: '10000', message: 'latest failed', traceId: null, data: null },
    { status },
  )));
}
