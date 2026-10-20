package com.aliyun.autowonder.squad;

import java.util.ArrayList;
import java.util.List;

/**
 * 单目标复制事务内创建的事务外资源台账：事务提交时无需处理，
 * 事务回滚时由编排层按台账补偿（对象存储尽力删除；本地加密密文随数据库事务回滚，无远端凭证资源需要删除）。
 * 台账只保存引用名与 ossRef，不保存任何明文凭证。
 */
public class SquadCopyResourceLedger {

    public static final String KIND_PACKAGE = "PACKAGE";
    public static final String KIND_SECRET_REF = "SECRET_REF";

    private final List<Entry> entries = new ArrayList<>();

    public void recordPackage(String label, String ossRef) {
        entries.add(new Entry(KIND_PACKAGE, label, ossRef));
    }

    public void recordSecretRef(String label) {
        entries.add(new Entry(KIND_SECRET_REF, label, null));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public record Entry(String kind, String label, String ossRef) {
    }
}
