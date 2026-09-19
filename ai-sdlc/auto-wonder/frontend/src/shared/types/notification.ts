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
}

export interface NotificationPage {
  items: Notification[];
  total: number;
}

export interface NotificationPref {
  type: string;
  inApp: boolean;
  dingtalk: boolean;
}
