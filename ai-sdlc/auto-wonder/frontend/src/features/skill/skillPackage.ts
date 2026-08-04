import JSZip from 'jszip';

export interface SkillPackageMetadata {
  name: string;
  description: string;
}

export interface SkillDirectoryReadResult {
  rootName: string;
  files: File[];
  metadata: SkillPackageMetadata;
}

export function parseSkillFrontmatter(content: string): SkillPackageMetadata {
  if (!content.startsWith('---')) {
    throw new Error('SKILL.md 缺少 YAML frontmatter');
  }
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!match) {
    throw new Error('SKILL.md frontmatter 格式不正确');
  }

  const lines = match[1].split(/\r?\n/);
  let name = '';
  let description = '';
  let readingDescriptionBlock = false;
  const descriptionLines: string[] = [];

  for (const line of lines) {
    if (readingDescriptionBlock) {
      if (/^\s+/.test(line) || line.trim() === '') {
        descriptionLines.push(line.replace(/^\s{2}/, ''));
        continue;
      }
      readingDescriptionBlock = false;
    }

    const nameMatch = line.match(/^name:\s*(.*)$/);
    if (nameMatch) {
      name = stripYamlQuote(nameMatch[1].trim());
      continue;
    }

    const descriptionMatch = line.match(/^description:\s*(.*)$/);
    if (descriptionMatch) {
      const value = descriptionMatch[1].trim();
      if (value === '|' || value === '>') {
        readingDescriptionBlock = true;
      } else {
        description = stripYamlQuote(value);
      }
    }
  }

  if (descriptionLines.length > 0) {
    description = descriptionLines.join('\n').trim();
  }
  if (!name || !description) {
    throw new Error('SKILL.md frontmatter 必须包含 name 和 description');
  }
  return { name, description };
}

export async function readSkillDirectory(filesLike: File[] | FileList): Promise<SkillDirectoryReadResult> {
  const files = Array.from(filesLike).filter((file) => !shouldIgnore(file));
  const skillMd = files.find((file) => relativePath(file).split('/').length === 2
    && relativePath(file).endsWith('/SKILL.md'));
  if (!skillMd) {
    throw new Error('请选择包含根目录 SKILL.md 的 skill 文件夹');
  }

  const rootName = relativePath(skillMd).split('/')[0];
  const metadata = parseSkillFrontmatter(await readFileText(skillMd));
  return { rootName, files, metadata };
}

export async function buildSkillZip(input: SkillDirectoryReadResult): Promise<File> {
  const zip = new JSZip();
  await Promise.all(input.files.map(async (file) => {
    const path = relativePath(file);
    const normalizedPath = path.startsWith(`${input.rootName}/`)
      ? path.substring(input.rootName.length + 1)
      : path;
    zip.file(normalizedPath, new Uint8Array(await file.arrayBuffer()));
  }));
  const blob = await zip.generateAsync({ type: 'blob' });
  return new File([blob], `${sanitizeFileName(input.metadata.name)}.zip`, { type: 'application/zip' });
}

function relativePath(file: File) {
  return ((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name).replace(/\\/g, '/');
}

function shouldIgnore(file: File) {
  const path = relativePath(file);
  return path.includes('/.git/') || path.includes('/node_modules/') || path.endsWith('/.DS_Store');
}

function stripYamlQuote(value: string) {
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
    return value.substring(1, value.length - 1);
  }
  return value;
}

function sanitizeFileName(name: string) {
  return name.replace(/[^A-Za-z0-9._-]/g, '-');
}

function readFileText(file: File): Promise<string> {
  const textReader = (file as File & { text?: () => Promise<string> }).text;
  if (typeof textReader === 'function') {
    return textReader.call(file);
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error || new Error('读取文件失败'));
    reader.readAsText(file);
  });
}
