package com.aliyun.autowonder.common;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Published package namespace boundary; historical pinned versions stay installable. */
public final class RuntimeNpmPackage {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[0-9A-Za-z.-]+)?$");

    private RuntimeNpmPackage() { }

    public static String spec(String version) {
        var match = VERSION.matcher(version);
        if (!match.matches()) throw new IllegalArgumentException("Invalid runtime npm version");
        var major = new BigInteger(match.group(1));
        var minor = new BigInteger(match.group(2));
        var patch = new BigInteger(match.group(3));
        boolean scoped = major.signum() > 0 || minor.compareTo(BigInteger.valueOf(3)) > 0
                || (minor.equals(BigInteger.valueOf(3)) && patch.compareTo(BigInteger.valueOf(33)) >= 0);
        return (scoped ? "@alicloud/autowonder@" : "autowonder@") + version;
    }
}
