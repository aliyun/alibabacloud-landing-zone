import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';

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
    return <Navigate to="/workspaces" replace />;
  }

  return <>{children}</>;
}
