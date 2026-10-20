import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useId } from 'react';
import { AuditOutlined, CheckCircleOutlined, CodeOutlined, CloudUploadOutlined, ExperimentOutlined, FileTextOutlined, PlayCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';
import './AboutAutoWonderPage.css';

// Intentionally copied from the login story: this page evolves independently.
const returnPath = 'M600 80H624Q640 80 640 96V160Q640 180 620 180H20Q0 180 0 160V96Q0 80 16 80H40';

const deliverySteps = [
  { id: 'clarification', accent: false, icon: <FileTextOutlined aria-hidden="true" /> },
  { id: 'start', accent: false, icon: <PlayCircleOutlined aria-hidden="true" /> },
  { id: 'development', icon: <CodeOutlined aria-hidden="true" />, accent: true },
  { id: 'verification', icon: <SafetyCertificateOutlined aria-hidden="true" />, accent: true },
  { id: 'review', icon: <AuditOutlined aria-hidden="true" />, accent: true },
  { id: 'deployment', icon: <CloudUploadOutlined aria-hidden="true" />, accent: true },
  { id: 'testing', icon: <ExperimentOutlined aria-hidden="true" />, accent: true },
  { id: 'delivery', icon: <CheckCircleOutlined aria-hidden="true" />, accent: true },
] as const;

export function AboutAutoWonderPage() {
  const { t } = useTranslation('about');
  const gridId = useId();
  const { data: branding } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const deploymentVersion =
    branding?.deploymentVersion?.trim() || DEFAULT_BRANDING.deploymentVersion;

  return (
    <section className="about-aw-page" aria-label={t('title')}>
      <PageHeading title={t('title')} />
      <header className="about-aw-header">
        <div className="about-aw-intro">
          <p className="about-aw-eyebrow">{t('tagline')}</p>
          <h2>{t('intro')}</h2>
        </div>
        <div className="about-aw-version" aria-label={t('version')}>
          <span className="about-aw-version-label">{t('currentVersion')}</span>
          <strong className="about-aw-version-number">{deploymentVersion}</strong>
        </div>
      </header>
      <figure className="about-aw-delivery" aria-label={t('flow')}>
        <svg className="about-aw-delivery-lines" viewBox="0 0 640 240" preserveAspectRatio="none" fill="none" aria-hidden="true">
          <defs>
            <pattern id={gridId} width="20" height="20" patternUnits="userSpaceOnUse">
              <circle cx="10" cy="10" r=".7" fill="var(--aw-border)" />
            </pattern>
          </defs>
          <rect x="-20" y="0" width="680" height="240" fill={`url(#${gridId})`} />
          <path className="about-aw-delivery-track" d="M40 80H600" />
          <path className="about-aw-delivery-return" d={returnPath} />
          <path className="about-aw-delivery-flow" pathLength="100" d="M40 80H600" />
          <path className="about-aw-delivery-flow about-aw-delivery-flow--return" pathLength="100" d={returnPath} />
        </svg>
        <ol className="about-aw-delivery-steps">
          {deliverySteps.map(({ id, icon, accent }) => (
            <li key={id}>
              <span className="about-aw-delivery-node">{icon}</span>
              <span className={accent ? 'about-aw-delivery-label about-aw-delivery-label--accent' : 'about-aw-delivery-label'}>{t(id)}</span>
            </li>
          ))}
        </ol>
        <figcaption>{t('iteration')}</figcaption>
      </figure>
    </section>
  );
}
