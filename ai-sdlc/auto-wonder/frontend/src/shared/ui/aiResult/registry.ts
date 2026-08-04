import type { ComponentType } from 'react';
import type { AiResultRendererProps } from './types';
import { RepoScanRenderer } from './RepoScanRenderer';
import { MemoryImportRenderer } from './MemoryImportRenderer';
import { SdlcGenRenderer } from './SdlcGenRenderer';
import { AgentConfigGenRenderer } from './AgentConfigGenRenderer';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const rendererRegistry: Record<string, ComponentType<AiResultRendererProps<any>>> = {
  REPO_SCAN: RepoScanRenderer,
  MEMORY_IMPORT: MemoryImportRenderer,
  SDLC_GEN: SdlcGenRenderer,
  AGENT_CONFIG_GEN: AgentConfigGenRenderer,
};
