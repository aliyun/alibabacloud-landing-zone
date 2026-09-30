package com.aliyun.autowonder.repo;

/**
 * 仓库名称必须映射为工作区下的单个目录段（workspace/repos/&lt;name&gt;）。
 * 与 Runtime engine 的单路径段约束对齐：拒绝路径分隔符、`.`/`..`、绝对路径
 * 与 Windows 盘符形式，且不允许首尾空白以外的空白名称。
 */
public final class RepoNameValidator {

    private RepoNameValidator() {
    }

    /**
     * @return null 表示合法；否则返回用于提示的可执行修正说明。
     */
    public static String validate(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "仓库名称不能为空";
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            return "仓库名称不能为空";
        }
        if (name.contains("/") || name.contains("\\")) {
            return invalidSegmentMessage(name);
        }
        if (".".equals(name) || "..".equals(name)) {
            return invalidSegmentMessage(name);
        }
        if (name.startsWith("~") || name.startsWith("%")) {
            return "仓库名称不能以 ~ 或 % 开头";
        }
        if (isWindowsDrive(name)) {
            return invalidSegmentMessage(name);
        }
        if (name.chars().anyMatch(c -> Character.isISOControl(c))) {
            return "仓库名称不能包含控制字符";
        }
        return null;
    }

    private static boolean isWindowsDrive(String name) {
        // C: / C:sub 形式在 Windows 下会被解释为绝对路径。
        return name.length() >= 2 && name.charAt(1) == ':'
                && ((name.charAt(0) >= 'a' && name.charAt(0) <= 'z')
                        || (name.charAt(0) >= 'A' && name.charAt(0) <= 'Z'));
    }

    private static String invalidSegmentMessage(String name) {
        return "仓库名称「" + name + "」必须是单级目录名（不含 / 或 \\ 等路径分隔符、"
                + "也不能是 . 或 ..、绝对路径）。如填写的是 namespace/repo-name 形式的标识，"
                + "请将完整标识填在 Git 仓库地址中，仓库名称只填单级目录名。";
    }
}
