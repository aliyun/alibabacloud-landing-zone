import type { WorkspaceRoleVO } from '@/features/settings/api';
import { WORKSPACE_DOMAINS } from '@/shared/auth/access';

export const mockRoles: WorkspaceRoleVO[] = [
  { id: 11, code: 'READ_ONLY', name: null, administrator: false, builtIn: true, readOnlyDomains: WORKSPACE_DOMAINS, version: 0, memberCount: 0 },
  { id: 12, code: 'READ_WRITE', name: null, administrator: false, builtIn: true, readOnlyDomains: [], version: 0, memberCount: 1 },
  { id: 13, code: 'ADMIN', name: null, administrator: true, builtIn: true, readOnlyDomains: [], version: 0, memberCount: 1 },
  { id: 14, code: null, name: '交付协作 / Delivery', administrator: false, builtIn: false, readOnlyDomains: ['SDLC'], version: 2, memberCount: 0 },
];
