import { Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { WorkflowEvidenceRef, WorkflowGraph } from './api';
import { ArrowRightOutlined, ArrowUpOutlined, BulbOutlined, NodeIndexOutlined } from '@ant-design/icons';
import { workflowNodeBadge, workflowNodeTone } from './workflowNodeCard';
import type { WorkflowSelection } from './WorkflowGraphCanvas';

const KIND_LABEL_KEYS = {
  MEMBER: 'workflow.kinds.MEMBER',
  HUMAN: 'workflow.kinds.HUMAN',
  EXTERNAL: 'workflow.kinds.EXTERNAL',
  UNRESOLVED: 'workflow.kinds.UNRESOLVED',
  HANDOFF: 'workflow.kinds.HANDOFF',
  REWORK: 'workflow.kinds.REWORK',
  ESCALATION: 'workflow.kinds.ESCALATION',
  MISSING_CONFIGURATION: 'workflow.kinds.MISSING_CONFIGURATION',
  AMBIGUOUS_TARGET: 'workflow.kinds.AMBIGUOUS_TARGET',
  CONFLICT: 'workflow.kinds.CONFLICT',
  INFORMATION: 'workflow.kinds.INFORMATION',
} as const;

const FINDING_COLORS: Record<string, string> = {
  MISSING_CONFIGURATION: 'red',
  AMBIGUOUS_TARGET: 'orange',
  CONFLICT: 'volcano',
  INFORMATION: 'blue',
};

const EDGE_COLORS: Record<string, string> = {
  HANDOFF: 'blue',
  REWORK: 'red',
  ESCALATION: 'orange',
};

export interface WorkflowDetailsProps {
  graph: WorkflowGraph | null | undefined;
  selection: WorkflowSelection | null;
  generatedAt?: string | null;
  onSelect?: (selection: WorkflowSelection) => void;
}

export function WorkflowDetails({ graph, selection, generatedAt, onSelect }: WorkflowDetailsProps) {
  const { t } = useTranslation('sdlc');
  const nodes = graph?.workflow?.nodes ?? [];
  const edges = graph?.workflow?.edges ?? [];
  const node = selection?.type === 'node' ? nodes.find((item) => item.id === selection.id) : undefined;
  const edge = selection?.type === 'edge' ? edges.find((item) => item.id === selection.id) : undefined;
  const labelOf = (id: string) => nodes.find((item) => item.id === id)?.label || id;

  const kindLabel = (kind: string) => (
    Object.prototype.hasOwnProperty.call(KIND_LABEL_KEYS, kind)
      ? t(KIND_LABEL_KEYS[kind as keyof typeof KIND_LABEL_KEYS])
      : kind
  );

  const renderEvidence = (refs: WorkflowEvidenceRef[] | undefined) => {
    if (!refs || refs.length === 0) return null;
    return (
      <div style={{ marginTop: 8 }}>
        <Typography.Text strong>{t('workflow.evidence')}</Typography.Text>
        {refs.map((ref, index) => (
          <div key={`${ref.sourceId}-${ref.fieldPath ?? ''}-${index}`} style={{ marginTop: 6 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {ref.fieldPath
                ? t('workflow.sourceWithField', { sourceId: ref.sourceId, fieldPath: ref.fieldPath })
                : ref.sourceId}
            </Typography.Text>
            <div
              data-testid="workflow-evidence-quote"
              className="aw-workflow-evidence"
            >
              {ref.quote}
            </div>
          </div>
        ))}
      </div>
    );
  };

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <div className="aw-workflow-detail-eyebrow">{edge ? t('workflow.edgeDetail') : t('workflow.nodeDetail')}</div>
      {node && (
        <section aria-label={t('workflow.node')}>
          <div className={`aw-workflow-detail-badge aw-workflow-tone-${workflowNodeTone(node)}`}>{workflowNodeBadge(node)}</div>
          <Typography.Title level={4}>{node.label || node.id}</Typography.Title>
          <Tag>{kindLabel(node.kind)}</Tag>
          <Typography.Title level={5} style={{ marginTop: 8, marginBottom: 4 }}>
            {t('workflow.responsibility')}
          </Typography.Title>
          <Typography.Paragraph style={{ marginBottom: 0 }}>{node.responsibility}</Typography.Paragraph>
          {(node.stepSummaries?.length ?? 0) > 0 && (
            <>
              <Typography.Title level={5} style={{ marginTop: 8, marginBottom: 4 }}>
                {t('workflow.steps')}
              </Typography.Title>
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {node.stepSummaries?.map((step) => (
                  <li key={step.stepId}>
                    <Typography.Text strong>{step.label}</Typography.Text>
                    <div>{step.description}</div>
                  </li>
                ))}
              </ul>
            </>
          )}
          {edges.some(item => item.source === node.id) && (
            <>
              <Typography.Title level={5}>{t('workflow.handoffRules')}</Typography.Title>
              {edges.filter(item => item.source === node.id).map(item => (
                <button type="button" className="aw-workflow-route" key={item.id} onClick={() => onSelect?.({ type: 'edge', id: item.id })}>
                  <span><ArrowRightOutlined aria-hidden="true" /> {labelOf(item.target)}</span>
                  <small>{item.condition}</small>
                </button>
              ))}
            </>
          )}
          {renderEvidence(node.evidenceRefs)}
        </section>
      )}

      {edge && (
        <section aria-label={t('workflow.edge')}>
          <div className="aw-workflow-detail-badge aw-workflow-tone-blue"><ArrowUpOutlined rotate={45} aria-hidden="true" /></div>
          <Space wrap>
            <Tag color={Object.prototype.hasOwnProperty.call(EDGE_COLORS, edge.kind) ? EDGE_COLORS[edge.kind] : undefined}>
              {kindLabel(edge.kind)}
            </Tag>
            {edge.isMainPath && <Tag color="green">{t('workflow.mainPathBadge')}</Tag>}
          </Space>
          <Typography.Paragraph style={{ marginTop: 8, marginBottom: 0 }}>
            {labelOf(edge.source)} → {labelOf(edge.target)}
          </Typography.Paragraph>
          <Typography.Title level={5} style={{ marginTop: 8, marginBottom: 4 }}>
            {t('workflow.condition')}
          </Typography.Title>
          <Typography.Paragraph style={{ marginBottom: 0 }}>{edge.condition}</Typography.Paragraph>
          {renderEvidence(edge.evidenceRefs)}
        </section>
      )}

      {!node && !edge && (
        <div>
          <div className="aw-workflow-detail-badge aw-workflow-tone-blue"><NodeIndexOutlined aria-hidden="true" /></div>
          <Typography.Title level={4}>{t('workflow.details')}</Typography.Title>
          <Typography.Paragraph type="secondary">{t('workflow.noSelection')}</Typography.Paragraph>
        </div>
      )}

      {generatedAt && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('workflow.generatedAt', { time: generatedAt })}
        </Typography.Text>
      )}
    </Space>
  );
}


/** Findings describe the whole squad and stay visible independently of the selected member. */
export function WorkflowFindings({ graph }: Pick<WorkflowDetailsProps, 'graph'>) {
  const { t } = useTranslation('sdlc');
  const findings = graph?.workflow.findings ?? [];
  const references = (finding: (typeof findings)[number]) => [
    ...(finding.nodeIds ?? []).map(id => graph?.workflow.nodes.find(node => node.id === id)?.label || id),
    ...(finding.edgeIds ?? []).map(id => graph?.workflow.edges.find(edge => edge.id === id)?.condition || id),
  ];
  return (
    <section className="aw-workflow-findings" aria-label={t('workflow.findings')}>
      <div className="aw-workflow-findings-head">
        <span className="aw-workflow-findings-icon"><BulbOutlined aria-hidden="true" /></span>
        <h3>{t('workflow.findings')}</h3>
      </div>
      {findings.length === 0 && <Typography.Text type="secondary">{t('workflow.noFindings')}</Typography.Text>}
      {findings.map(finding => (
        <div key={finding.id} className="aw-workflow-finding">
          <Tag color={FINDING_COLORS[finding.kind]}>{t(KIND_LABEL_KEYS[finding.kind])}</Tag>
          <span>{finding.summary}</span>
          {references(finding).length > 0 && <div className="aw-workflow-finding-source">
            {t('workflow.references')}: {references(finding).join(t('workflow.referenceSeparator'))}
          </div>}
          {finding.evidenceRefs?.map((ref, index) => (
            <div key={`${ref.sourceId}-${index}`} className="aw-workflow-evidence">
              <div className="aw-workflow-finding-source">{ref.fieldPath ? t('workflow.sourceWithField', { sourceId: ref.sourceId, fieldPath: ref.fieldPath }) : ref.sourceId}</div>
              {ref.quote}
            </div>
          ))}
        </div>
      ))}
    </section>
  );
}
