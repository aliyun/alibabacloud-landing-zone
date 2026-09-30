import { PageHeading } from '@/shared/ui/PageHeading';
import { useId } from 'react';
import { AuditOutlined, CheckCircleOutlined, CodeOutlined, CloudUploadOutlined, ExperimentOutlined, FileTextOutlined, PlayCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { BRANDING_QUERY_KEY, DEFAULT_BRANDING, getPublicBranding } from '@/features/platform/brandingApi';
import './AboutAutoWonderPage.css';

// Intentionally copied from the login story: this page evolves independently.
const returnPath = 'M600 80H624Q640 80 640 96V160Q640 180 620 180H20Q0 180 0 160V96Q0 80 16 80H40';

const deliverySteps = [
  { label: '需求澄清', icon: <FileTextOutlined /> },
  { label: '启动', icon: <PlayCircleOutlined /> },
  { label: '开发', icon: <CodeOutlined />, accent: true },
  { label: '验证', icon: <SafetyCertificateOutlined />, accent: true },
  { label: '评审', icon: <AuditOutlined />, accent: true },
  { label: '部署', icon: <CloudUploadOutlined />, accent: true },
  { label: '测试', icon: <ExperimentOutlined />, accent: true },
  { label: '交付', icon: <CheckCircleOutlined />, accent: true },
];

export function AboutAutoWonderPage() {
  const gridId = useId();
  const { data: branding } = useQuery({
    queryKey: BRANDING_QUERY_KEY,
    queryFn: getPublicBranding,
  });
  const deploymentVersion =
    branding?.deploymentVersion?.trim() || DEFAULT_BRANDING.deploymentVersion;

  return (
    <section className="about-aw-page" aria-label="关于平台">
      <PageHeading title="关于平台" />
      <header className="about-aw-header">
        <div className="about-aw-intro">
          <p className="about-aw-eyebrow">AI Native SDLC Platform</p>
          <h2>软件产品自动交付平台</h2>
        </div>
        <div className="about-aw-version" aria-label="平台版本">
          <span className="about-aw-version-label">当前平台版本</span>
          <strong className="about-aw-version-number">{deploymentVersion}</strong>
        </div>
      </header>
      <figure className="about-aw-delivery" aria-label="软件自动交付流程示意：需求澄清、启动、开发、验证、评审、部署、测试、交付，持续迭代">
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
          {deliverySteps.map(({ label, icon, accent }) => (
            <li key={label}>
              <span className="about-aw-delivery-node">{icon}</span>
              <span className={accent ? 'about-aw-delivery-label about-aw-delivery-label--accent' : 'about-aw-delivery-label'}>{label}</span>
            </li>
          ))}
        </ol>
        <figcaption>持续迭代</figcaption>
      </figure>
    </section>
  );
}
