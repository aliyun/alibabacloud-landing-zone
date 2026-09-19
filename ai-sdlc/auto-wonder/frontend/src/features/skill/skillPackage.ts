import JSZip from 'jszip';
import type { SkillPackageFile } from './api';

export async function buildDirectoryZip(filesLike: File[] | FileList): Promise<File> {
  const files = Array.from(filesLike).filter((file) => !shouldIgnore(file));
  if (!files.length || files.length > 500 || files.reduce((total, file) => total + file.size, 0) > 100 * 1024 * 1024) {
    throw new Error('请选择文件夹，最多 500 个文件，总大小不超过 100 MB');
  }
  const rootName = relativePath(files[0]).split('/')[0];
  const zip = new JSZip();
  await Promise.all(files.map(async (file) => {
    const path = relativePath(file);
    if (!path.startsWith(`${rootName}/`)) throw new Error('请选择一个完整的文件夹');
    zip.file(path.substring(rootName.length + 1), new Uint8Array(await file.arrayBuffer()), { createFolders: false });
  }));
  const blob = await zip.generateAsync({ type: 'blob' });
  return new File([blob], `${sanitizeFileName(rootName)}.zip`, { type: 'application/zip' });
}

/** 与 antd Tree 的 DataNode 结构兼容，便于直接作为 treeData 传入。 */
export interface SkillPackageTreeNode {
  title: string;
  key: string;
  isLeaf?: boolean;
  children?: SkillPackageTreeNode[];
}

export function formatBytes(size: number): string {
  if (!Number.isFinite(size) || size <= 0) {
    return '0 B';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = size;
  let unit = -1;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/**
 * 后端给的是扁平路径列表，这里按 '/' 还原层级。
 * 显式 DIR 记录与隐式补出的目录必须去重，否则同名目录会出现两次。
 */
export function buildPackageTree(files: SkillPackageFile[]): SkillPackageTreeNode[] {
  const root: SkillPackageTreeNode[] = [];
  const dirs = new Map<string, SkillPackageTreeNode[]>();
  const dirPaths = new Set<string>();
  const collectDirPath = (dirPath: string) => {
    let current = dirPath;
    // 已登记说明其祖先也已登记，可提前终止
    while (current && !dirPaths.has(current)) {
      dirPaths.add(current);
      current = parentOf(current);
    }
  };
  for (const file of files) {
    if (file.dir) {
      collectDirPath(file.path);
    } else {
      collectDirPath(parentOf(file.path));
    }
  }

  const ensureDir = (dirPath: string): SkillPackageTreeNode[] => {
    const existing = dirs.get(dirPath);
    if (existing) {
      return existing;
    }
    const children: SkillPackageTreeNode[] = [];
    // 先登记再挂到父级，避免自引用路径导致无限递归
    dirs.set(dirPath, children);
    const node: SkillPackageTreeNode = { title: lastSegment(dirPath), key: dirPath, children };
    const parent = parentOf(dirPath);
    (parent ? ensureDir(parent) : root).push(node);
    return children;
  };

  for (const file of files) {
    if (file.dir) {
      ensureDir(file.path);
      continue;
    }
    // 退化包里 a 与 a/b 并存时 a 已是目录，再挂同名叶子会让 Tree 拿到重复 key
    if (dirPaths.has(file.path)) {
      continue;
    }
    const parent = parentOf(file.path);
    (parent ? ensureDir(parent) : root).push({
      title: `${file.name}  ${formatBytes(file.size)}`,
      key: file.path,
      isLeaf: true,
    });
  }

  return sortTree(root);
}

function relativePath(file: File) {
  return ((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name).replace(/\\/g, '/');
}

function shouldIgnore(file: File) {
  const path = relativePath(file);
  return path.includes('/.git/') || path.includes('/node_modules/') || path.endsWith('/.DS_Store');
}

function sanitizeFileName(name: string) {
  return name.replace(/[^A-Za-z0-9._-]/g, '-');
}

function parentOf(path: string): string {
  const index = path.lastIndexOf('/');
  return index < 0 ? '' : path.substring(0, index);
}

function lastSegment(path: string): string {
  const index = path.lastIndexOf('/');
  return index < 0 ? path : path.substring(index + 1);
}

/** 目录优先，其后按路径末段排序；按 title 排会把体积后缀也算进去。 */
function sortTree(nodes: SkillPackageTreeNode[]): SkillPackageTreeNode[] {
  const sorted = [...nodes].sort((a, b) => {
    const aIsDir = a.children ? 1 : 0;
    const bIsDir = b.children ? 1 : 0;
    if (aIsDir !== bIsDir) {
      return bIsDir - aIsDir;
    }
    return lastSegment(a.key).localeCompare(lastSegment(b.key));
  });
  sorted.forEach((node) => {
    if (node.children) {
      node.children = sortTree(node.children);
    }
  });
  return sorted;
}
