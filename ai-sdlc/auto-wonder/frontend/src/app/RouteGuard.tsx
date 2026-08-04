import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';

interface RouteGuardProps {
  children: React.ReactNode;
  requireOrg?: boolean;
}

export function RouteGuard({ children, requireOrg = true }: RouteGuardProps) {
  const location = useLocation();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
  const currentOrg = useAuthStore((s) => s.currentOrg);

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (requireOrg && !currentOrg) {
    return <Navigate to="/orgs" replace />;
  }

  return <>{children}</>;
}
