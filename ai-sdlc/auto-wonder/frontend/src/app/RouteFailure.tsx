import { useEffect } from 'react';
import { useLocation, useNavigate, useRouteError } from 'react-router-dom';
import { ContentFailure } from '@/shared/components/ContentErrorBoundary';

export function RouteFailure() {
  const error = useRouteError();
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => { console.error('Route rendering failed', error); }, [error]);
  return <ContentFailure onRetry={() => {
    // A same-location navigation resets the router's error boundary, without a document reload.
    void navigate(location.pathname + location.search + location.hash, { replace: true, state: location.state });
  }} />;
}
