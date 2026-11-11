import { useInRouterContext, useLocation } from 'react-router-dom';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export function ContentFailure({ onRetry }: { onRetry?: () => void }) {
  const { t } = useTranslation('common');
  return <section role="alert" className="aw-content-failure" style={{ padding: 24 }}>
    <p>{t('failure.title')}</p>
    <p>{t('failure.description')}</p>
    {onRetry && <button type="button" onClick={onRetry}>{t('failure.retry')}</button>}
  </section>;
}

type BoundaryProps = { children: ReactNode; onRetry?: () => void; resetKey?: string };

class Boundary extends Component<BoundaryProps, { failed: boolean; resetKey?: string }> {
  state: { failed: boolean; resetKey?: string } = { failed: false, resetKey: this.props.resetKey };
  static getDerivedStateFromProps(props: BoundaryProps, state: { resetKey?: string }) {
    return props.resetKey !== state.resetKey ? { failed: false, resetKey: props.resetKey } : null;
  }
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Content rendering failed', error, info.componentStack);
  }
  render() {
    return this.state.failed ? <ContentFailure onRetry={() => {
      this.props.onRetry?.();
      this.setState({ failed: false });
    }} /> : this.props.children;
  }
}

function RouteContentBoundary(props: BoundaryProps) {
  const location = useLocation();
  return <Boundary {...props} resetKey={location.key} />;
}

export function ContentErrorBoundary(props: BoundaryProps) {
  const inRouter = useInRouterContext();
  return inRouter ? <RouteContentBoundary {...props} /> : <Boundary {...props} />;
}
