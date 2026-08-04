package com.aliyun.autowonder.redis;

import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

public enum RedisExtendCommand implements ProtocolCommand {
    EXSET("EXSET"),
    EXGET("EXGET"),
    EXINCRBY("EXINCRBY");

    private final byte[] raw;

    RedisExtendCommand(String command) {
        raw = SafeEncoder.encode(command);
    }

    @Override
    public byte[] getRaw() {
        return raw;
    }
}
