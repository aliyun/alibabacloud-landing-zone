import { describe, expect, it } from 'vitest';
import type { Category, Skill } from './api';
import {
  buildCategoryTree, buildCategorySkillGroups, categoryMoveExclusions,
} from './categoryTree';

let nextId = 1;

function category(fields: Partial<Category> & Pick<Category, 'name'>): Category {
  return {
    id: fields.id ?? nextId++,
    parentId: fields.parentId ?? null,
    name: fields.name,
    description: fields.description ?? null,
    path: fields.path ?? fields.name,
    version: 0,
    gmtCreate: '2026-09-16T10:00:00Z',
  };
}

function skill(id: number, categoryId: number | null): Skill {
  return {
    id,
    name: `能力 ${id}`,
    type: 'SKILL',
    installSpec: 'built-in',
    description: '',
    categoryId,
    version: 1,
    gmtCreate: '2026-09-16T10:00:00Z',
  };
}

describe('buildCategoryTree', () => {
  it('nests children under their parents and keeps top-level nodes at the root', () => {
    const tree = buildCategoryTree([
      category({ id: 1, name: '编码' }),
      category({ id: 2, parentId: 1, name: '前端' }),
      category({ id: 3, parentId: 2, name: 'Vue' }),
      category({ id: 4, name: '办公' }),
    ]);

    expect(tree.map((node) => node.name)).toEqual(['编码', '办公']);
    expect(tree[0].children.map((node) => node.name)).toEqual(['前端']);
    expect(tree[0].children[0].children.map((node) => node.name)).toEqual(['Vue']);
    expect(tree[1].children).toHaveLength(0);
  });

  it('treats orphan nodes whose parent is missing as top-level', () => {
    const tree = buildCategoryTree([
      category({ id: 2, parentId: 99, name: '孤儿' }),
      category({ id: 1, name: '顶级' }),
    ]);

    // 父分类 99 不在列表里（跨项目/已删除）：孤儿按顶级处理而不是被静默丢弃
    expect(tree.map((node) => node.name)).toEqual(['孤儿', '顶级']);
  });

  it('preserves server name ordering at every level', () => {
    const tree = buildCategoryTree([
      category({ id: 9, name: 'A' }),
      category({ id: 10, name: 'B' }),
      category({ id: 3, parentId: 9, name: 'React' }),
      category({ id: 2, parentId: 9, name: 'Vue' }),
    ]);
    expect(tree.map(node => node.id)).toEqual([9, 10]);
    expect(tree[0].children.map(node => node.name)).toEqual(['React', 'Vue']);
  });
});

describe('categoryMoveExclusions', () => {
  it('blocks the category itself and all of its descendants, at any depth', () => {
    const categories = [
      category({ id: 1, name: '编码' }),
      category({ id: 2, parentId: 1, name: '前端' }),
      category({ id: 3, parentId: 2, name: 'Vue' }),
      category({ id: 4, parentId: 3, name: 'Vue3' }),
      category({ id: 5, name: '办公' }),
      category({ id: 6, parentId: 5, name: '文档' }),
    ];

    expect(categoryMoveExclusions(categories, 2)).toEqual(new Set([2, 3, 4]));
    expect(categoryMoveExclusions(categories, 1)).toEqual(new Set([1, 2, 3, 4]));
    expect(categoryMoveExclusions(categories, 4)).toEqual(new Set([4]));
  });

  it('does not block sibling branches', () => {
    const categories = [
      category({ id: 1, name: '编码' }),
      category({ id: 2, parentId: 1, name: '前端' }),
      category({ id: 3, parentId: 1, name: '后端' }),
    ];

    expect(categoryMoveExclusions(categories, 2)).toEqual(new Set([2]));
  });
});

describe('buildCategorySkillGroups', () => {
  it('groups skills by their direct category and aggregates descendant counts', () => {
    const tree = buildCategoryTree([
      category({ id: 1, name: '编码' }),
      category({ id: 2, parentId: 1, name: '前端' }),
      category({ id: 3, parentId: 2, name: 'Vue' }),
    ]);
    const { groups, uncategorized } = buildCategorySkillGroups(tree, [
      skill(101, 1),
      skill(102, 1),
      skill(103, 3),
    ]);

    expect(uncategorized).toHaveLength(0);
    expect(groups).toHaveLength(1);
    const coding = groups[0];
    expect(coding.category.id).toBe(1);
    expect(coding.direct.map((item) => item.id)).toEqual([101, 102]);
    // 父节点只汇总数量，不重复展示后代分类的能力
    expect(coding.totalCount).toBe(3);
    expect(coding.children).toHaveLength(1);
    const frontend = coding.children[0];
    expect(frontend.direct).toHaveLength(0);
    expect(frontend.totalCount).toBe(1);
    expect(frontend.children[0].direct.map((item) => item.id)).toEqual([103]);
  });

  it('prunes branches without any matching skill but keeps their ancestors', () => {
    const tree = buildCategoryTree([
      category({ id: 1, name: '编码' }),
      category({ id: 2, parentId: 1, name: '前端' }),
      category({ id: 3, parentId: 2, name: 'Vue' }),
      category({ id: 4, name: '办公' }),
    ]);
    const { groups } = buildCategorySkillGroups(tree, [skill(101, 3)]);

    // 只有 Vue 下有能力：办公整支剪掉；编码/前端因 totalCount>0 保留，前端 direct 为空
    expect(groups).toHaveLength(1);
    expect(groups[0].category.id).toBe(1);
    expect(groups[0].children).toHaveLength(1);
    expect(groups[0].children[0].category.id).toBe(2);
    expect(groups[0].children[0].direct).toHaveLength(0);
    expect(groups[0].children[0].children[0].category.id).toBe(3);
  });

  it('collects skills without a category into uncategorized instead of a group', () => {
    const tree = buildCategoryTree([category({ id: 1, name: '编码' })]);
    const { groups, uncategorized } = buildCategorySkillGroups(tree, [
      skill(101, null),
      skill(102, 999),
    ]);

    // 未分类与指向不存在分类的能力都不进入分类分组
    expect(groups).toHaveLength(0);
    expect(uncategorized.map((item) => item.id)).toEqual([101, 102]);
  });

  it('returns empty groups when no skills match any category', () => {
    const tree = buildCategoryTree([category({ id: 1, name: '编码' })]);
    const grouping = buildCategorySkillGroups(tree, []);

    expect(grouping.groups).toHaveLength(0);
    expect(grouping.uncategorized).toHaveLength(0);
  });
});
