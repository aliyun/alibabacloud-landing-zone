package com.aliyun.autowonder.util;

import org.springframework.util.StringUtils;

public class MetricUtils {

    public static String namePrefix(String prefix, String name, String... labels) {
        if (StringUtils.isEmpty(name)) {
            throw new NullPointerException("name is null.");
        }
        if (labels.length % 2 == 1) {
            throw new IllegalArgumentException("labels is illegal.");
        }
        StringBuilder sb = new StringBuilder();
        if (!StringUtils.isEmpty(prefix)) {
            sb.append(prefix);
            sb.append('_');
        }
        sb.append(name);
        sb.append(':');
        for (int i = 0; i < labels.length; i += 2) {
            if (sb.length() != 0) {
                sb.append(',');
            }
            sb.append(labels[i]);
            sb.append('=');
            sb.append(labels[i + 1]);
        }
        return sb.toString();
    }

    public static String name(String name, String... labels) {
        return namePrefix("", name, labels);
    }
}
