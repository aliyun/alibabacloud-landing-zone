import { describe, expect, it } from 'vitest';
import { buildSkillZip, parseSkillFrontmatter, readSkillDirectory } from './skillPackage';

function file(path: string, content: string) {
  const f = new File([content], path.split('/').pop() || path, { type: 'text/plain' });
  Object.defineProperty(f, 'webkitRelativePath', { value: path });
  return f;
}

describe('skillPackage', () => {
  it('parses name and block description from SKILL.md frontmatter', () => {
    const meta = parseSkillFrontmatter(`---
name: custom-skill
description: |
  First line.
  Second line.
---
# Custom Skill
`);

    expect(meta).toEqual({
      name: 'custom-skill',
      description: 'First line.\nSecond line.',
    });
  });

  it('reads selected directory metadata from root SKILL.md', async () => {
    const result = await readSkillDirectory([
      file('custom-skill/SKILL.md', '---\nname: custom-skill\ndescription: Demo skill\n---\n'),
      file('custom-skill/scripts/run.sh', 'echo run'),
    ]);

    expect(result.metadata.name).toBe('custom-skill');
    expect(result.metadata.description).toBe('Demo skill');
    expect(result.rootName).toBe('custom-skill');
  });

  it('builds a zip file for upload', async () => {
    const result = await readSkillDirectory([
      file('custom-skill/SKILL.md', '---\nname: custom-skill\ndescription: Demo skill\n---\n'),
    ]);

    const zip = await buildSkillZip(result);

    expect(zip.name).toBe('custom-skill.zip');
    expect(zip.size).toBeGreaterThan(0);
    expect(zip.type).toBe('application/zip');
  });
});
