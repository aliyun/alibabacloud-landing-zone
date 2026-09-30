import { ApartmentOutlined } from '@ant-design/icons';
import { Tag } from 'antd';
import type { WorkerFinding, RiskLevel } from '../types';

const RISK_STYLES: Record<RiskLevel, { bg: string; border: string; color: string }> = {
  high: { bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-error) 30%, var(--aw-border))', color: 'var(--aw-error)' },
  medium: { bg: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))', color: 'var(--aw-warning)' },
  low: { bg: 'var(--aw-raised)', border: 'var(--aw-text)', color: 'var(--aw-muted)' },
};

interface SdlcLinkIssuesProps {
  findings: WorkerFinding[];
}

export function SdlcLinkIssues({ findings }: SdlcLinkIssuesProps) {
  return (
    <div style={{ background: 'var(--aw-panel)', border: '1px solid var(--aw-border)', borderRadius: 10, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <ApartmentOutlined style={{ color: 'var(--aw-warning)' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--aw-text)' }}>SDLC 链路问题</span>
      </div>
      <div style={{ display: 'grid', gap: 9 }}>
        {findings.map((item) => {
          const style = RISK_STYLES[item.severity];
          return (
            <div key={`${item.worker}-${item.link}`} style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, borderBottom: '1px solid var(--aw-border)', paddingBottom: 9 }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--aw-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.worker}</span>
                  <Tag style={{ margin: 0, borderRadius: 4, color: style.color, borderColor: style.border, background: style.bg }}>{item.link}</Tag>
                </div>
                <div style={{ fontSize: 12, color: 'var(--aw-muted)', lineHeight: '18px' }}>{item.issue}，{item.impact}</div>
              </div>
              <span style={{ alignSelf: 'start', fontSize: 11, color: style.color, fontWeight: 700 }}>{item.signal}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
