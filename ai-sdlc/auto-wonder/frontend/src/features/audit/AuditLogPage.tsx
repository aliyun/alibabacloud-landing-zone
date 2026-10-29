import { AUDIT_LOG_PAGE_V2_ENABLED } from './featureFlags';
import { AuditLogPageV2 } from './AuditLogPageV2';
import { LegacyAuditLogPage } from './LegacyAuditLogPage';

interface AuditLogPageProps {
  pageV2Enabled?: boolean;
}

export function AuditLogPage({ pageV2Enabled = AUDIT_LOG_PAGE_V2_ENABLED }: AuditLogPageProps) {
  return pageV2Enabled ? <AuditLogPageV2 /> : <LegacyAuditLogPage />;
}
