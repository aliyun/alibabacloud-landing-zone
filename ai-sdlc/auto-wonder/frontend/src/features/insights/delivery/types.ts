export interface DeliveryMetrics {
  delivered: number; human: number | null; humanTotal: number; humanStartedDeliveries: number;
  autonomous: number; autonomousRequirements: number; duration: number | null;
  durationSamples: number; durationMissing: number; resolution: number | null;
  resolutionEligible: number; resolutionSolved: number; resolutionObserving: number;
  night: number; holiday: number; workhours: number; offhours: number; regularhours: number;
  skill: number; versions: number; versionTypes: Record<string, number>;
}
export interface DeliveryReport {
  days: number; start: string; end: string; summary: DeliveryMetrics;
  weeks: { start: string; end: string; partial: boolean; metrics: DeliveryMetrics }[];
  unclosedSessions: number; unclassifiedEvents: number;
}
export interface DeliveryResponse {
  status: 'READY' | 'COMPUTING' | 'REFRESHING' | 'ERROR' | 'BUSY';
  report: DeliveryReport | null; errorCode: string | null;
}
export type Metric = 'delivered' | 'resolution' | 'human' | 'autonomous' | 'duration' | 'offtime' | 'workhours' | 'skill';

export const metricValue = (metrics: DeliveryMetrics, metric: Metric): number | null =>
  metric === 'offtime' ? metrics.night + metrics.holiday : metrics[metric];
