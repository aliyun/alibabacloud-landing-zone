import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Empty, theme } from 'antd';
import { AimOutlined, MinusOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Graph } from '@antv/g6';
import type { EdgeData, NodeData } from '@antv/g6';
import type { WorkflowEdge, WorkflowGraph, WorkflowNode } from './api';

import { CARD_WIDTH, CARD_HEIGHT, layoutWorkflow, routeWorkflowEdges } from './workflowLayout';
import { createWorkflowNodeCard } from './workflowNodeCard';
import './workflow.css';

export type WorkflowViewMode = 'main' | 'all';
export type WorkflowSelection = { type: 'node' | 'edge'; id: string };

const DEFAULT_GRAPH_WIDTH = 900;
const DEFAULT_GRAPH_HEIGHT = 565;

interface GraphColors {
  panel: string;
  text: string;
  primary: string;
  warning: string;
  error: string;
}

export interface WorkflowElements {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

/**
 * The main-path view keeps only edges flagged `isMainPath` plus the nodes they connect. When no
 * edge is on the main path, every node stays visible so the canvas never looks empty.
 */
export function selectWorkflowElements(
  graph: WorkflowGraph | null | undefined,
  mode: WorkflowViewMode,
): WorkflowElements {
  const nodes = graph?.workflow?.nodes ?? [];
  const edges = graph?.workflow?.edges ?? [];
  if (mode === 'all') {
    return { nodes, edges };
  }
  const mainEdges = edges.filter((edge) => edge.isMainPath);
  if (mainEdges.length === 0) {
    return { nodes, edges: [] };
  }
  const visible = new Set<string>();
  mainEdges.forEach((edge) => {
    visible.add(edge.source);
    visible.add(edge.target);
  });
  return { nodes: nodes.filter((node) => visible.has(node.id)), edges: mainEdges };
}

export function truncateLabel(text: string, max = 32): string {
  if (!text) return '';
  const characters = Array.from(text);
  return characters.length > max ? `${characters.slice(0, max - 1).join('')}…` : text;
}

export function edgeStroke(
  kind: WorkflowEdge['kind'],
  colors: Pick<GraphColors, 'primary' | 'warning' | 'error'>,
): string {
  if (kind === 'HANDOFF') return colors.primary;
  return kind === 'REWORK' ? colors.error : colors.warning;
}

export function edgeLabel(
  kind: WorkflowEdge['kind'],
  condition: string,
  escalationLabel: string,
): string {
  const text = truncateLabel(condition, 12);
  return kind === 'ESCALATION' ? `${escalationLabel}: ${text}` : text;
}

function getCanvasSize(container: HTMLDivElement) {
  const rect = container.getBoundingClientRect();
  return {
    width: Math.round(rect.width) || DEFAULT_GRAPH_WIDTH,
    height: Math.round(rect.height) || DEFAULT_GRAPH_HEIGHT,
  };
}

function elementSelection(elementId: unknown): WorkflowSelection | null {
  if (typeof elementId !== 'string') return null;
  if (elementId.startsWith('node:')) return { type: 'node', id: elementId.slice('node:'.length) };
  if (elementId.startsWith('edge:')) return { type: 'edge', id: elementId.slice('edge:'.length) };
  return null;
}

async function fitGraph(instance: Graph, readable = false) {
  await instance.fitView({}, false);
  if (typeof instance.getZoom !== 'function' || instance.destroyed) return;
  if (instance.getZoom() > 1) await instance.zoomTo(1, false);
  if (readable && instance.getZoom() < 0.65) await instance.zoomTo(0.65, false);
}

export interface WorkflowGraphCanvasProps {
  graph: WorkflowGraph | null | undefined;
  mode: WorkflowViewMode;
  onSelect: (selection: WorkflowSelection) => void;
  selection?: WorkflowSelection | null;
}

export function WorkflowGraphCanvas({ graph, mode, onSelect, selection }: WorkflowGraphCanvasProps) {
  const { t } = useTranslation('sdlc');
  const { token } = theme.useToken();
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const [renderFailed, setRenderFailed] = useState(false);

  const elements = useMemo(() => selectWorkflowElements(graph, mode), [graph, mode]);

  const colors = useMemo<GraphColors>(() => ({
    panel: token.colorBgContainer,
    text: token.colorText,
    primary: '#8999b8',
    warning: '#c9a66c',
    error: '#d18386',
  }), [token.colorBgContainer, token.colorText, token.colorPrimary, token.colorWarning, token.colorError]);

  const positions = useMemo(() => layoutWorkflow(graph?.workflow.nodes ?? [], graph?.workflow.edges ?? []), [graph]);
  const routes = useMemo(() => routeWorkflowEdges(graph?.workflow.edges ?? [], positions), [graph, positions]);
  const nodeData = useMemo<NodeData[]>(() => elements.nodes.map(node => ({
    id: `node:${node.id}`,
    data: { refType: 'node', refId: node.id, kind: node.kind },
    style: {
      ...positions[node.id], size: [CARD_WIDTH, CARD_HEIGHT], dx: -CARD_WIDTH / 2, dy: -CARD_HEIGHT / 2,
      innerHTML: createWorkflowNodeCard(node, node.stepSummaries?.length
        ? t('workflow.nodeSteps', { count: node.stepSummaries.length })
        : t(`workflow.kinds.${node.kind}`), () => onSelect({ type: 'node', id: node.id })),
      ports: (['top', 'right', 'bottom', 'left'] as const).map(placement => ({ key: placement, placement })),
    },
  })), [elements.nodes, positions, onSelect, t]);
  const edgeData = useMemo<EdgeData[]>(() => elements.edges.map(edge => ({
    id: `edge:${edge.id}`, source: `node:${edge.source}`, target: `node:${edge.target}`,
    data: { refType: 'edge', refId: edge.id, kind: edge.kind },
    style: {
      ...routes[edge.id],
      labelText: edgeLabel(edge.kind, edge.condition, t('workflow.kinds.ESCALATION')),
      labelFontSize: 10, labelAutoRotate: false, labelPadding: [3, 6], labelOffsetY: -16,
      labelBackground: true, labelBackgroundFill: colors.panel, labelBackgroundOpacity: 0.94,
      labelFill: edgeStroke(edge.kind, colors), labelMaxWidth: 74,
      labelWordWrap: true, labelMaxLines: 2, labelTextOverflow: 'ellipsis',
      stroke: edgeStroke(edge.kind, colors), lineWidth: 1.5,
      lineDash: edge.kind === 'HANDOFF' ? undefined : edge.kind === 'REWORK' ? [6, 5] : [4, 5],
      endArrow: true, endArrowSize: 6, cursor: 'pointer', pointerEvents: 'stroke',
    },
  })), [elements.edges, routes, colors, t]);

  const selectionRef = useRef(selection);
  const edgesRef = useRef(elements.edges);
  selectionRef.current = selection;
  edgesRef.current = elements.edges;
  const syncSelection = useCallback((instance: Graph | null) => {
    const selected = selectionRef.current;
    containerRef.current?.querySelectorAll<HTMLElement>('[data-workflow-node]').forEach(card => {
      card.setAttribute('aria-pressed', String(selected?.type === 'node' && selected.id === card.dataset.workflowNode));
    });
    if (instance && typeof instance.setElementState === 'function') {
      const states = Object.fromEntries(edgesRef.current.map(edge => [
        `edge:${edge.id}`, selected?.type === 'edge' && selected.id === edge.id ? ['selected'] : [],
      ]));
      void instance.setElementState(states, false).catch(() => undefined);
    }
  }, []);
  useEffect(() => { syncSelection(graphRef.current); }, [selection, elements.edges, syncSelection]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }
    if (nodeData.length === 0) {
      setRenderFailed(false);
      return;
    }
    setRenderFailed(false);
    const { width, height } = getCanvasSize(container);
    const graphInstance = new Graph({
      container,
      width,
      height,
      autoFit: 'view',
      padding: [64, 40, 48, 40],
      zoomRange: [0.2, 2],
      autoResize: true,
      animation: false,
      data: { nodes: nodeData, edges: edgeData },
      node: { type: 'html' },
      edge: { type: 'polyline', style: { radius: 10 }, state: { selected: { stroke: '#5067e9', lineWidth: 2.5 } } },
      behaviors: ['drag-canvas', 'zoom-canvas'],
    });

    const handleSelect = (event: { target?: { id?: string } }) => {
      const selection = elementSelection(event?.target?.id);
      if (selection) onSelect(selection);
    };
    graphInstance.on('node:click', handleSelect as Parameters<typeof graphInstance.on>[1]);
    graphInstance.on('edge:click', handleSelect as Parameters<typeof graphInstance.on>[1]);
    graphRef.current = graphInstance;
    let rendered = false;
    void graphInstance.render()
      .then(async () => {
        if (graphRef.current === graphInstance) {
          await fitGraph(graphInstance, nodeData.length > 12 || width < 620);
          if (graphRef.current === graphInstance) { rendered = true; syncSelection(graphInstance); }
        }
        return undefined;
      })
      .catch(() => {
        if (graphRef.current === graphInstance) setRenderFailed(true);
      });

    let resizeObserver: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => {
        if (graphRef.current !== graphInstance) return;
        const next = getCanvasSize(container);
        graphInstance.setSize(next.width, next.height);
        if (rendered) void fitGraph(graphInstance, nodeData.length > 12 || next.width < 620).catch(() => undefined);
      });
      resizeObserver.observe(container);
    }

    return () => {
      resizeObserver?.disconnect();
      if (graphRef.current === graphInstance) {
        graphInstance.destroy();
        graphRef.current = null;
      }
    };
  }, [nodeData, edgeData, onSelect, syncSelection]);

  if (elements.nodes.length === 0) {
    return <Empty description={t('workflow.graphEmpty')} image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div className="aw-workflow-canvas-shell">
      <div className="aw-workflow-canvas-label">{t('workflow.canvasLabel')}</div>
      <div ref={containerRef} data-testid="workflow-graph" className="aw-workflow-canvas" />
      <div className="aw-workflow-zoom">
        <Button size="small" aria-label={t('workflow.fitView')} icon={<AimOutlined aria-hidden="true" />}
          onClick={() => { if (graphRef.current) void fitGraph(graphRef.current); }}>{t('workflow.panorama')}</Button>
        <Button size="small" aria-label={t('workflow.zoomOut')} icon={<MinusOutlined aria-hidden="true" />}
          onClick={() => { void graphRef.current?.zoomBy(0.8, false); }} />
        <Button size="small" aria-label={t('workflow.zoomIn')} icon={<PlusOutlined aria-hidden="true" />}
          onClick={() => { void graphRef.current?.zoomBy(1.25, false); }} />
      </div>
      {renderFailed && <Alert className="aw-workflow-render-error" type="error" showIcon message={t('workflow.graphError')} />}
    </div>
  );
}
