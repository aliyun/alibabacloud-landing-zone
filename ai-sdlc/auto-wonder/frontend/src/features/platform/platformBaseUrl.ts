import { DEFAULT_BRANDING, type PlatformBranding } from './brandingApi';

const MCP_PATH_SUFFIX = '/api/mcp';

function stripTrailingSlashes(value: string): string {
  return value.replace(/\/+$/, '');
}

/**
 * 用户访问平台的地址。私有化部署没有统一域名，任何要发给别人的链接都得现算：
 * 先用管理员填的「部署域名」，其次用部署级 `autowonder.public-base-url`（后端以
 * `mcpBaseUrl = public-base-url + /api/mcp` 下发），最后才退回当前 origin。
 * 优先级与后端 PlatformBrandingService.effectivePublicBaseUrl 一致——种子的
 * DEFAULT_DOMAIN 是内网地址，在私有化部署里不可达，等同于未配置。
 */
export function resolvePlatformBaseUrl(branding?: PlatformBranding | null): string {
  const domain = branding?.domain?.trim();
  if (domain && domain !== DEFAULT_BRANDING.domain) {
    return stripTrailingSlashes(domain);
  }
  const mcpBaseUrl = branding?.mcpBaseUrl?.trim();
  if (mcpBaseUrl?.endsWith(MCP_PATH_SUFFIX)) {
    const base = stripTrailingSlashes(mcpBaseUrl.slice(0, -MCP_PATH_SUFFIX.length));
    if (base) return base;
  }
  return stripTrailingSlashes(window.location.origin);
}
