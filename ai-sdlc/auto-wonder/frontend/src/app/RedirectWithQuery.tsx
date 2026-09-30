import { Navigate, useLocation } from 'react-router-dom';

/**
 * Legacy-route redirect that keeps the incoming query string. `<Navigate to="/x" />`
 * would silently drop it, breaking deep links such as `/squads?squadId=7`.
 * `tab` leads the query so the URL matches the canonical hub deep-link format.
 */
export function RedirectWithQuery({ to, tab }: { to: string; tab?: string }) {
  const { search } = useLocation();
  const incoming = new URLSearchParams(search);
  const params = new URLSearchParams();
  if (tab !== undefined) {
    params.set('tab', tab);
  }
  incoming.forEach((value, key) => params.append(key, value));
  const query = params.toString();
  return <Navigate to={query ? `${to}?${query}` : to} replace />;
}
