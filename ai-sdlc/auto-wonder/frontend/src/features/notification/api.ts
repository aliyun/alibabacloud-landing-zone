import { apiClient } from '@/shared/api/client';
import type { Notification, NotificationPage } from '@/shared/types/notification';

export type NotificationStatusFilter = 'UNREAD' | 'READ';

export interface NotificationListParams {
  status?: NotificationStatusFilter;
  page: number;
  size: number;
}

export async function listNotifications(params: NotificationListParams): Promise<NotificationPage> {
  const resp = await apiClient.get<NotificationPage>('/api/notifications', { params });
  return resp.data;
}

/** 按 ID 读取本人通知；分页外的通知也能打开详情，归属由服务端约束。 */
export async function getNotification(id: number): Promise<Notification> {
  const resp = await apiClient.get<Notification>(`/api/notifications/${id}`);
  return resp.data;
}

export function markNotificationRead(id: number) {
  return apiClient.post(`/api/notifications/${id}/read`);
}

export function markAllNotificationsRead() {
  return apiClient.post('/api/notifications/read-all');
}

export function deleteNotification(id: number) {
  return apiClient.delete(`/api/notifications/${id}`);
}
