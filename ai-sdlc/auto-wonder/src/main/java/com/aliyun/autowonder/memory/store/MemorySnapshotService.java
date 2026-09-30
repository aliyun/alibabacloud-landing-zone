package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.store.dto.MemorySnapshotVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemorySnapshotService {
    private static final Pattern MARKDOWN_LINK = Pattern.compile("(\\[[^]]*])\\(([^)]+)\\)");

    public MemorySnapshotVO compose(List<MemorySnapshotVO.Store> sourceStores) {
        List<MemorySnapshotVO.Store> stores = sourceStores == null ? new ArrayList<>()
                : new ArrayList<>(sourceStores);
        stores.sort(Comparator.comparingInt((MemorySnapshotVO.Store store) -> rank(store.scope()))
                .thenComparingLong(MemorySnapshotVO.Store::id));
        StringBuilder shared = new StringBuilder();
        StringBuilder personal = new StringBuilder();
        for (MemorySnapshotVO.Store store : stores) {
            MemorySnapshotVO.Document memoryIndex = store.documents().stream()
                    .filter(document -> "MEMORY.md".equals(document.path()))
                    .findFirst().orElse(null);
            StringBuilder target = "AGENT".equals(store.scope()) ? personal : shared;
            appendSection(target, store, memoryIndex == null ? "" : memoryIndex.contentMd());
        }
        // The snapshot is the lossless on-disk authority. The runtime applies
        // Claude's 200-line/25,000-byte bound only when rendering the system
        // prompt; truncating here would make a later edit erase the hidden tail.
        String composed = shared.toString() + personal;
        return new MemorySnapshotVO(composed, List.copyOf(stores));
    }

    private void appendSection(StringBuilder target, MemorySnapshotVO.Store store, String content) {
        if (!target.isEmpty()) target.append('\n');
        target.append("<!-- ").append(store.scope()).append(" memory -->\n");
        String rewritten = rewriteLinks(content == null ? "" : content, prefix(store));
        target.append(rewritten);
        if (!rewritten.isEmpty() && !rewritten.endsWith("\n")) target.append('\n');
    }

    private String rewriteLinks(String markdown, String prefix) {
        if (prefix.isEmpty()) {
            return markdown;
        }
        Matcher matcher = MARKDOWN_LINK.matcher(markdown);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String target = matcher.group(2);
            String replacement = target.contains(":") || target.startsWith("/") || target.startsWith("#")
                    ? target : prefix + target;
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + "(" + replacement + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String prefix(MemorySnapshotVO.Store store) {
        return switch (store.scope()) {
            case "ORG" -> "team/org/";
            case "SQUAD" -> "team/squad-" + store.ownerRef() + "/";
            default -> "";
        };
    }

    private int rank(String scope) {
        return switch (scope) {
            case "ORG" -> 0;
            case "SQUAD" -> 1;
            case "AGENT" -> 2;
            default -> 3;
        };
    }
}
