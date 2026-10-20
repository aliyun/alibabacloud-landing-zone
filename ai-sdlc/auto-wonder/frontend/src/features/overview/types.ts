/** 交付总览近期统计与当前用户待处理工单（后端 /api/insights/delivery-overview）。 */

export interface DeliveryOverviewDailyCount {
  date: string;
  count: number;
}

export interface DeliveryOverviewPeriod {
  startDate: string;
  endDate: string;
  count: number;
}

export interface DeliveryOverviewCreated {
  count: number;
  daily: DeliveryOverviewDailyCount[];
}

export interface DeliveryOverviewDelivered {
  count: number;
  dailyAverage: number;
  previous: DeliveryOverviewPeriod;
  /** 上期为 0 时无可比基数，为 null。 */
  changePercent: number | null;
}

export interface DeliveryOverview {
  startDate: string;
  endDate: string;
  timezone: string;
  dataThrough: string;
  daysInRange: number;
  created: DeliveryOverviewCreated;
  delivered: DeliveryOverviewDelivered;
}

export interface PendingWorkitem {
  id: number;
  title: string;
  statusName: string | null;
  statusCategory: string;
  priority: number;
  /** AWAITING_DECISION / AWAITING_HANDLING / IN_PROGRESS。 */
  reason: string;
  gmtModified: string;
}

export interface PendingWorkitemPage {
  list: PendingWorkitem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CreatedWorkitem {
  id: number;
  title: string;
  statusName: string | null;
  /** 当前状态节点分类：INIT / IN_PROGRESS / DONE / CANCELED，用于状态标签着色。 */
  statusCategory: string | null;
  priority: number;
  createdAt: string;
}

export interface CreatedWorkitemPage {
  list: CreatedWorkitem[];
  total: number;
  pageNum: number;
  pageSize: number;
}
