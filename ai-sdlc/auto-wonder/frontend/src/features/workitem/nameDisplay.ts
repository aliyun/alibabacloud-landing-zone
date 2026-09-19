/**
 * 工单人员姓名展示工具。
 *
 * 后端下发的展示名常带身份编号后缀（"蔡何(10000)"、"AW全栈开发(40013)"），
 * 列表/看板/详情统一在此去除后缀只保留名称本身；
 * 括号内非纯数字（有业务含义）的内容不受影响。
 */

const ID_SUFFIX_PATTERN = /[（(]\s*\d+\s*[)）]\s*$/;

/** 去掉名称末尾的纯数字编号括号后缀；剥完为空时回退原值，避免把名称本身删没。 */
export function stripAssigneeIdSuffix(name: string): string {
  const stripped = name.replace(ID_SUFFIX_PATTERN, '').trim();
  return stripped || name;
}

/** 优先取展示名、缺省回退登录名，两者都做编号后缀清理；都为空返回 null。 */
export function displayNameWithoutId(
  displayName?: string | null,
  fallbackName?: string | null,
): string | null {
  const raw = displayName || fallbackName;
  if (!raw) return null;
  return stripAssigneeIdSuffix(raw);
}
