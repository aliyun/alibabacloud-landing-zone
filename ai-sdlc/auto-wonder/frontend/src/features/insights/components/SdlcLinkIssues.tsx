import { ApartmentOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import type { WorkerFinding, RiskLevel } from '../types';

const RISK_STYLES: Record<RiskLevel, { bg: string; border: string; color: string }> = {
  high: { bg: '#fef2f2', border: '#fecaca', color: '#dc2626' },
  medium: { bg: '#fffbeb', border: '#fde68a', color: '#d97706' },
  low: { bg: '#f9fafb', border: '#e5e7eb', color: '#6b7280' },
};

interface SdlcLinkIssuesProps {
  findings: WorkerFinding[];
}

export function SdlcLinkIssues({ findings }: SdlcLinkIssuesProps) {
  return (
    <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <ApartmentOutlined style={{ color: '#d97706' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: '#374151' }}>SDLC 链路问题</span>
      </div>
      <div style={{ display: 'grid', gap: 9 }}>
        {findings.map((item) => {
          const style = RISK_STYLES[item.severity];
          return (
            <div key={`${item.worker}-${item.link}`} style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, borderBottom: '1px solid #f3f4f6', paddingBottom: 9 }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: '#111827', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.worker}</span>
                  <Tag style={{ margin: 0, borderRadius: 4, color: style.color, borderColor: style.border, background: style.bg }}>{item.link}</Tag>
                </div>
                <div style={{ fontSize: 12, color: '#4b5563', lineHeight: '18px' }}>{item.issue}，{item.impact}</div>
              </div>
              <span style={{ alignSelf: 'start', fontSize: 11, color: style.color, fontWeight: 700 }}>{item.signal}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
