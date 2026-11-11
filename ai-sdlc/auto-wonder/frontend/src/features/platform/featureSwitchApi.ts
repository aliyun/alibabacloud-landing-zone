import { apiClient } from '@/shared/api/client';

export interface FeatureSwitches {
  duplicateWorkitemIntentEnabled: boolean;
  version: number;
  userRegistrationReviewEnabled: boolean;
  userRegistrationReviewVersion: number;
  workspaceCreationDefaultAllowed: boolean;
  workspaceCreationDefaultAllowedVersion: number;
}

export interface UpdateFeatureSwitchesParams {
  duplicateWorkitemIntentEnabled?: boolean;
  expectedVersion?: number;
  userRegistrationReviewEnabled?: boolean;
  userRegistrationReviewVersion?: number;
  workspaceCreationDefaultAllowed?: boolean;
  workspaceCreationDefaultAllowedVersion?: number;
}

export const FEATURE_SWITCH_QUERY_KEY = ['platform-feature-switches'] as const;

export async function getFeatureSwitches(): Promise<FeatureSwitches> {
  const resp = await apiClient.get<FeatureSwitches>('/api/platform/feature-switches');
  return resp.data;
}

export async function updateFeatureSwitches(params: UpdateFeatureSwitchesParams): Promise<FeatureSwitches> {
  const resp = await apiClient.put<FeatureSwitches>('/api/platform/feature-switches', params);
  return resp.data;
}
