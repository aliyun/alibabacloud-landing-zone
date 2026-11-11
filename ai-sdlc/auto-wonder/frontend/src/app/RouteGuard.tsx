import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { isAuthEntryPath } from '@/shared/auth/returnPath';

interface RouteGuardProps {
  children: React.ReactNode;
  requireWorkspace?: boolean;
}

export function RouteGuard({ children, requireWorkspace = true }: RouteGuardProps) {
  const location = useLocation();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const currentWorkspace = useAuthStore((s) => s.currentWorkspace);

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (requireWorkspace && !currentWorkspace) {
    // 选择空间后要回到用户原本要访问的页面；认证入口自身不作为回跳目标，避免回到登录循环。
    const blockedTarget = `${location.pathname}${location.search}${location.hash}`;
    const search = isAuthEntryPath(blockedTarget)
      ? ''
      : `?returnTo=${encodeURIComponent(blockedTarget)}`;
    return <Navigate to={{ pathname: '/workspaces', search }} replace />;
  }

  return <>{children}</>;
}
