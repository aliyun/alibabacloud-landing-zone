import type { Category, Skill } from './api';

export interface CategoryNode extends Category {
  children: CategoryNode[];
}

/** 从平铺分类构建项目级分类树；孤儿节点（父分类缺失）按顶级处理。 */
export function buildCategoryTree(categories: Category[]): CategoryNode[] {
  const byId = new Map<number, CategoryNode>();
  for (const category of categories) {
    byId.set(category.id, { ...category, children: [] });
  }
  const roots: CategoryNode[] = [];
  for (const node of byId.values()) {
    const parent = node.parentId != null ? byId.get(node.parentId) : undefined;
    if (parent) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }
  // 保留服务端按名称、ID 的顺序，避免浏览器与数据库字符排序规则不同。
  return roots;
}

/** 修改分类时上级选项要排除自身及其后代，防止移动到自己的子树下形成环。 */
export function categoryMoveExclusions(categories: Category[], id: number): Set<number> {
  const blocked = new Set<number>([id]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const category of categories) {
      if (category.parentId != null && blocked.has(category.parentId) && !blocked.has(category.id)) {
        blocked.add(category.id);
        changed = true;
      }
    }
  }
  return blocked;
}

export interface CategorySkillGroup {
  category: CategoryNode;
  /** 直接挂在本分类下的能力（父节点只汇总数量，不重复展示子分类的能力）。 */
  direct: Skill[];
  /** 本分类及其后代分类中匹配的能力总数。 */
  totalCount: number;
  children: CategorySkillGroup[];
}

export interface CategorySkillGrouping {
  groups: CategorySkillGroup[];
  uncategorized: Skill[];
}

/**
 * 按分类树分组：没有匹配能力的分类整支剪掉（其祖先因 totalCount > 0 自然保留），
 * 未分类能力单独成组、渲染时置底。
 */
export function buildCategorySkillGroups(tree: CategoryNode[], skills: Skill[]): CategorySkillGrouping {
  const knownCategoryIds = new Set<number>();
  const collectCategoryIds = (nodes: CategoryNode[]) => {
    for (const node of nodes) {
      knownCategoryIds.add(node.id);
      collectCategoryIds(node.children);
    }
  };
  collectCategoryIds(tree);
  const byCategory = new Map<number, Skill[]>();
  const uncategorized: Skill[] = [];
  for (const skill of skills) {
    // categoryId 指向已不存在的分类（删除并发等）时按未分类兜底，不能在分组视图里丢掉
    if (skill.categoryId != null && knownCategoryIds.has(skill.categoryId)) {
      const list = byCategory.get(skill.categoryId);
      if (list) {
        list.push(skill);
      } else {
        byCategory.set(skill.categoryId, [skill]);
      }
    } else {
      uncategorized.push(skill);
    }
  }
  function build(nodes: CategoryNode[]): CategorySkillGroup[] {
    const groups: CategorySkillGroup[] = [];
    for (const node of nodes) {
      const children = build(node.children);
      const direct = byCategory.get(node.id) ?? [];
      const totalCount = direct.length + children.reduce((sum, child) => sum + child.totalCount, 0);
      if (totalCount > 0) {
        groups.push({ category: node, direct, totalCount, children });
      }
    }
    return groups;
  }
  return { groups: build(tree), uncategorized };
}
