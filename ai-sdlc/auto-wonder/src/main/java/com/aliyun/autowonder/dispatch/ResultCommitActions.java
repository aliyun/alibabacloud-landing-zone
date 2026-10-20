package com.aliyun.autowonder.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** A completion-scoped queue; callbacks run after the transaction manager releases JDBC resources. */
final class ResultCommitActions implements AutoCloseable {
    private static final ThreadLocal<ResultCommitActions> CURRENT = new ThreadLocal<>();
    private final Map<String, Runnable> actions = new LinkedHashMap<>();
    static ResultCommitActions open() {
        if (CURRENT.get() != null) throw new IllegalStateException("nested result commit scope");
        ResultCommitActions scope = new ResultCommitActions();
        CURRENT.set(scope);
        return scope;
    }
    static boolean defer(String key, Runnable action) {
        ResultCommitActions scope = CURRENT.get();
        if (scope == null) return false;
        scope.actions.putIfAbsent(key, action);
        return true;
    }
    static void cancel(String key) {
        ResultCommitActions scope = CURRENT.get();
        if (scope != null) scope.actions.remove(key);
    }
    void committed() {
        close();
        actions.forEach((key, action) -> {
            try { action.run(); }
            catch (RuntimeException failure) {
                LoggerFactory.getLogger(ResultCommitActions.class).warn(
                        "result committed; deferred action will be reconciled key={}", key, failure);
            }
        });
    }
    @Override public void close() { CURRENT.remove(); }
}
