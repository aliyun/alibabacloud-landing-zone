import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  confirmPrdVersion,
  createPrdDraftFrom,
  getPrdDeliveryAcceptance,
  getPrdDesign,
  getPrdSummary,
  getPrdVersion,
  listPrdDeliveries,
  listPrdVersions,
  selectPrdTarget,
  submitPrdForReview,
  type ConfirmVersionRequest,
  type SelectTargetRequest,
} from './api';

/** Poll fast while a generation task is in flight, slowly when idle. */
const ACTIVE_POLL_MS = 3000;
const IDLE_POLL_MS = 30000;

export function designKey(workitemId: string | number) {
  return ['prd', 'design', String(workitemId)] as const;
}
export function summaryKey(workitemId: string | number) {
  return ['prd', 'summary', String(workitemId)] as const;
}
export function versionsKey(workitemId: string | number) {
  return ['prd', 'versions', String(workitemId)] as const;
}
export function versionKey(workitemId: string | number, versionId: string | number) {
  return ['prd', 'version', String(workitemId), String(versionId)] as const;
}
export function deliveriesKey(workitemId: string | number) {
  return ['prd', 'deliveries', String(workitemId)] as const;
}
export function acceptanceKey(workitemId: string | number, dispatchId: string | number) {
  return ['prd', 'acceptance', String(workitemId), String(dispatchId)] as const;
}

export function usePrdDesign(workitemId: string | number, enabled = true) {
  return useQuery({
    queryKey: designKey(workitemId),
    queryFn: () => getPrdDesign(workitemId),
    enabled: enabled && !!workitemId,
  });
}

/** Read-only summary for the workitem card; returns null data when the workitem has no design. */
export function usePrdSummary(workitemId: string | number, enabled = true) {
  return useQuery({
    queryKey: summaryKey(workitemId),
    queryFn: () => getPrdSummary(workitemId),
    enabled: enabled && !!workitemId,
  });
}

export function usePrdVersions(workitemId: string | number, enabled = true) {
  return useQuery({
    queryKey: versionsKey(workitemId),
    queryFn: () => listPrdVersions(workitemId),
    enabled: enabled && !!workitemId,
  });
}

export function usePrdVersion(workitemId: string | number, versionId: string | number | null) {
  return useQuery({
    queryKey: versionKey(workitemId, versionId ?? 0),
    queryFn: () => getPrdVersion(workitemId, versionId as string | number),
    enabled: !!workitemId && versionId != null,
  });
}

export function usePrdDeliveries(workitemId: string | number, enabled = true) {
  return useQuery({
    queryKey: deliveriesKey(workitemId),
    queryFn: () => listPrdDeliveries(workitemId),
    enabled: enabled && !!workitemId,
  });
}

/**
 * Acceptance results for one delivery. Polls while anything is still unverified so a late executor
 * report shows up without a manual refresh, and stops once every requirement has a verdict.
 */
export function usePrdDeliveryAcceptance(
  workitemId: string | number,
  dispatchId: string | number | null,
) {
  return useQuery({
    queryKey: acceptanceKey(workitemId, dispatchId ?? 0),
    queryFn: () => getPrdDeliveryAcceptance(workitemId, dispatchId as string | number),
    enabled: !!workitemId && dispatchId != null,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (!data) return ACTIVE_POLL_MS;
      return data.complete ? IDLE_POLL_MS : ACTIVE_POLL_MS;
    },
  });
}

/** One invalidation helper shared by every mutation, so the workbench never shows a stale version. */
function useInvalidatePrd(workitemId: string | number) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: designKey(workitemId) });
    void queryClient.invalidateQueries({ queryKey: summaryKey(workitemId) });
    void queryClient.invalidateQueries({ queryKey: versionsKey(workitemId) });
    void queryClient.invalidateQueries({ queryKey: deliveriesKey(workitemId) });
  };
}

export function useSelectPrdTarget(workitemId: string | number) {
  const invalidate = useInvalidatePrd(workitemId);
  return useMutation({
    mutationFn: (request: SelectTargetRequest) => selectPrdTarget(workitemId, request),
    onSuccess: invalidate,
  });
}

export function useCreatePrdDraft(workitemId: string | number) {
  const invalidate = useInvalidatePrd(workitemId);
  return useMutation({
    mutationFn: ({ versionId, changeSummary }: { versionId: string | number; changeSummary?: string }) =>
      createPrdDraftFrom(workitemId, versionId, changeSummary),
    onSuccess: invalidate,
  });
}

export function useSubmitPrdForReview(workitemId: string | number) {
  const invalidate = useInvalidatePrd(workitemId);
  return useMutation({
    mutationFn: (versionId: string | number) => submitPrdForReview(workitemId, versionId),
    onSuccess: invalidate,
  });
}

export function useConfirmPrdVersion(workitemId: string | number) {
  const invalidate = useInvalidatePrd(workitemId);
  return useMutation({
    mutationFn: (request: ConfirmVersionRequest) => confirmPrdVersion(workitemId, request),
    onSuccess: invalidate,
  });
}
