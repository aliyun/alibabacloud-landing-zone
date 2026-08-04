export type WorkType = 'REQ' | 'TASK' | 'BUG';
export type NodeCategory = 'INIT' | 'IN_PROGRESS' | 'DONE' | 'CANCELED';

export interface StatusTemplate {
  id: number;
  workType: WorkType;
  name: string;
  isDefault: boolean;
  gmtCreate: string;
  gmtModified: string;
}

export interface StatusNode {
  id: number;
  templateId: number;
  code: string;
  name: string;
  category: NodeCategory;
  sort: number;
  gmtCreate: string;
}

export interface StatusTransition {
  id: number;
  templateId: number;
  fromNodeId: number;
  toNodeId: number;
  name: string;
  gmtCreate: string;
}

export interface TemplateDetail extends StatusTemplate {
  nodes: StatusNode[];
  transitions: StatusTransition[];
}
