import { apiClient } from '@/shared/api/client';
import { i18n, normalizeLocale } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import type {
  ClarificationAttachment,
  ClarificationConversation,
  ClarificationTurnEvent,
} from './types';

const base = (workitemId: number | string) =>
  `/api/workitems/${workitemId}/clarification-conversations`;

export function createClientMessageId(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  if (typeof webCrypto?.getRandomValues === 'function') {
    const bytes = webCrypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `fallback-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export async function listClarificationConversations(
  workitemId: number | string,
  agentId: number,
): Promise<ClarificationConversation[]> {
  const resp = await apiClient.get<ClarificationConversation[]>(base(workitemId), {
    params: { agentId },
  });
  return resp.data;
}

export async function getOrCreateClarificationConversation(
  workitemId: number | string,
  agentId: number,
): Promise<ClarificationConversation> {
  const resp = await apiClient.post<ClarificationConversation>(base(workitemId), { agentId });
  return resp.data;
}

export async function getClarificationConversation(
  workitemId: number | string,
  conversationId: number,
): Promise<ClarificationConversation> {
  const resp = await apiClient.get<ClarificationConversation>(
    `${base(workitemId)}/${conversationId}`,
  );
  return resp.data;
}

export interface SubmitClarificationTurnOptions {
  attachmentIds?: number[];
  /** 由调用方持有以便失败重试复用：服务端按它去重，避免超时重发产生两条消息。 */
  clientMessageId?: string;
}

export async function submitClarificationTurn(
  workitemId: number | string,
  conversationId: number,
  content: string,
  options: SubmitClarificationTurnOptions = {},
): Promise<void> {
  const clientMessageId = options.clientMessageId ?? createClientMessageId();
  await apiClient.post(`${base(workitemId)}/${conversationId}/turns`, {
    content,
    clientMessageId,
    attachmentIds: options.attachmentIds ?? [],
  });
}

/** 单文件上限 20MB，与 ClarificationAttachmentService.MAX_FILE_BYTES 对齐。 */
export async function uploadClarificationAttachment(
  workitemId: number | string,
  conversationId: number,
  file: File,
  options: { signal?: AbortSignal } = {},
): Promise<ClarificationAttachment> {
  const formData = new FormData();
  formData.append('file', file);
  const resp = await apiClient.post<ClarificationAttachment>(
    `${base(workitemId)}/${conversationId}/attachments`,
    formData,
    {
      signal: options.signal,
      transformRequest: [(data, headers) => {
        // 交给浏览器生成 multipart boundary，不能沿用实例默认的 application/json。
        if (headers) {
          headers.delete?.('Content-Type');
          delete headers['Content-Type'];
          delete headers['content-type'];
        }
        return data;
      }],
    },
  );
  return resp.data;
}

export async function deleteClarificationAttachment(
  workitemId: number | string,
  conversationId: number,
  attachmentId: number,
): Promise<void> {
  await apiClient.delete(`${base(workitemId)}/${conversationId}/attachments/${attachmentId}`);
}

async function attachmentBytes(
  workitemId: number | string,
  conversationId: number,
  attachmentId: number,
  mode: 'preview' | 'download',
): Promise<Blob> {
  // 这两个端点返回裸字节而非 Result 信封，走 apiClient 会被响应拦截器判为失败；
  // 也不能用 window.open —— 工作空间权限校验依赖 Authorization 头。
  const token = useAuthStore.getState().accessToken;
  const response = await fetch(
    `${base(workitemId)}/${conversationId}/attachments/${attachmentId}/${mode}`,
    {
      headers: {
        'Accept-Language': normalizeLocale(i18n.language),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    },
  );
  if (!response.ok) {
    throw new Error(await attachmentErrorMessage(response));
  }
  return response.blob();
}

async function attachmentErrorMessage(response: Response): Promise<string> {
  try {
    const text = (await response.text()).trim();
    if (text) return text;
  } catch {
    // 二进制或空响应体：退回状态文案。
  }
  return response.statusText || i18n.t('workitem:clarification.attachmentLoadFailed');
}

export async function fetchClarificationAttachmentPreview(
  workitemId: number | string,
  conversationId: number,
  attachmentId: number,
): Promise<Blob> {
  return attachmentBytes(workitemId, conversationId, attachmentId, 'preview');
}

/** 浏览器直接下载：后端统一按 octet-stream + attachment 下发，避免 HTML/SVG 内联执行。 */
export async function downloadClarificationAttachment(
  workitemId: number | string,
  conversationId: number,
  attachment: Pick<ClarificationAttachment, 'id' | 'name'>,
): Promise<void> {
  const blob = await attachmentBytes(workitemId, conversationId, attachment.id, 'download');
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = attachment.name;
  link.rel = 'noreferrer';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // 同步 revoke 会让 Safari/Firefox 在浏览器接管 blob 之前失效 URL。
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function cancelClarificationTurn(
  workitemId: number | string,
  conversationId: number,
  turnId: number,
): Promise<void> {
  await apiClient.post(
    `${base(workitemId)}/${conversationId}/turns/${turnId}/cancel`,
  );
}

/** content 以字符串承载：requestedSchema 是任意 JSON Schema，答案结构由 Agent 决定，
 *  原样透传回执行器最安全，服务端与前端都不重排。 */
export async function replyClarificationElicitation(
  workitemId: number | string,
  conversationId: number,
  requestId: string,
  action: 'accept' | 'decline',
  content?: Record<string, unknown>,
): Promise<void> {
  await apiClient.post(
    `${base(workitemId)}/${conversationId}/elicitations/${encodeURIComponent(requestId)}/reply`,
    { action, content: content ? JSON.stringify(content) : null },
  );
}

/** 打开会话时触发带外命令探针：服务端去重后拉起执行器抓取斜杠命令，
 *  结果经实时 acp_commands 事件回推；失败静默，下次打开重试。 */
export async function refreshClarificationCommands(
  workitemId: number | string,
  conversationId: number,
): Promise<void> {
  await apiClient.post(`${base(workitemId)}/${conversationId}/commands/refresh`);
}

/** 现有 GET /events 只支持 afterId 且上限 200，取不全一轮，故单开按轮次端点。 */
export async function getClarificationTurnEvents(
  workitemId: number | string,
  conversationId: number,
  turnId: number,
  afterId?: number,
): Promise<ClarificationTurnEvent[]> {
  const resp = await apiClient.get<ClarificationTurnEvent[]>(
    `${base(workitemId)}/${conversationId}/turns/${turnId}/events`,
    ...(afterId === undefined ? [] : [{ params: { afterId } }]),
  );
  return resp.data;
}
