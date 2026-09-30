export const REPO_NAME_INVALID_MESSAGE =
  '仓库名称必须为单级目录名，不能包含 / 或 \\、. 或 ..、绝对路径。如填写的是 namespace/repo-name 形式的标识，请将完整标识填在仓库地址中，仓库名称只填单级目录名';

export function isValidRepoName(value?: string): boolean {
  const name = value?.trim();
  if (!name) {
    return true;
  }
  if (name.includes('/') || name.includes('\\')) {
    return false;
  }
  if (name === '.' || name === '..') {
    return false;
  }
  if (/^[a-zA-Z]:/u.test(name)) {
    return false;
  }
  if (name.startsWith('~') || name.startsWith('%')) {
    return false;
  }
  return !/\p{Cc}/u.test(name);
}
