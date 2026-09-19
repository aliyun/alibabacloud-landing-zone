import { apiClient } from '@/shared/api/client';
import type { NotificationPage } from '@/shared/types/notification';

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

export function markNotificationRead(id: number) {
  return apiClient.post(`/api/notifications/${id}/read`);
}

export function markAllNotificationsRead() {
  return apiClient.post('/api/notifications/read-all');
}

export function deleteNotification(id: number) {
  return apiClient.delete(`/api/notifications/${id}`);
}
