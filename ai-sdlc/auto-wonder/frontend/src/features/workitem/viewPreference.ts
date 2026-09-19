/**
 * 工单列表视图（看板/表格）偏好的浏览器记忆。
 *
 * 与归属筛选偏好（scope）一致：切换时立即写入 localStorage，
 * 无记录、记录无效或存储不可用时回退默认看板视图。
 * 偏好只在当前浏览器、当前站点内生效，不进入路由或服务端状态。
 */

export type WorkitemViewMode = 'kanban' | 'table';

const VIEW_STORAGE_KEY = 'autowonder.workitems.view';
const DEFAULT_VIEW: WorkitemViewMode = 'kanban';

export function readWorkitemViewPreference(): WorkitemViewMode {
  try {
    const stored = window.localStorage.getItem(VIEW_STORAGE_KEY);
    if (stored === 'kanban' || stored === 'table') {
      return stored;
    }
  } catch {
    // localStorage 不可用（隐私模式/被禁用）时静默回退默认视图
  }
  return DEFAULT_VIEW;
}

export function writeWorkitemViewPreference(mode: WorkitemViewMode): void {
  try {
    window.localStorage.setItem(VIEW_STORAGE_KEY, mode);
  } catch {
    // 存储不可用时不影响切换本身，仅放弃记忆
  }
}
