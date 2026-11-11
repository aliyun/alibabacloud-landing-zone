export interface NotificationLink {
  kind: 'SUBMITTED' | 'DUPLICATE';
  title: string;
  url: string;
}

export interface Notification {
  id: number;
  type: string;
  title: string;
  content: string | null;
  link: string | null;
  refType: string | null;
  refId: number | null;
  status: 'UNREAD' | 'READ';
  gmtCreate: string;
  relatedLinks: NotificationLink[] | null;
}

export interface NotificationPage {
  items: Notification[];
  total: number;
}

/** 实时事件只带定位信息，正文由鉴权详情接口补齐。 */
export interface NotificationCreatedPayload {
  workspaceId: number;
  recipientId: number;
  notificationId: number;
}

export interface NotificationPref {
  type: string;
  inApp: boolean;
  dingtalk: boolean;
}
