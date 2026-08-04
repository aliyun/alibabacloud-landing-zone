package com.aliyun.autowonder.redis;

import redis.clients.jedis.Protocol;
import redis.clients.jedis.params.Params;
import redis.clients.jedis.util.SafeEncoder;

import java.util.ArrayList;
import java.util.Collections;

public class ExtendParams extends Params {

    private static final String VER = "ver";
    private static final String ABS = "abs";
    private static final String XX = "xx";
    private static final String NX = "nx";
    private static final String PX = "px";
    private static final String EX = "ex";

    public ExtendParams() {
    }

    public static ExtendParams extendParams() {
        return new ExtendParams();
    }

    public ExtendParams ver(int version) {
        addParam(VER, version);
        return this;
    }

    public ExtendParams abs(int absoluteVersion) {
        addParam(ABS, absoluteVersion);
        return this;
    }

    public ExtendParams ex(int secondsToExpire) {
        addParam(EX, secondsToExpire);
        return this;
    }

    public ExtendParams px(long millisecondsToExpire) {
        addParam(PX, millisecondsToExpire);
        return this;
    }

    public ExtendParams nx() {
        addParam(NX);
        return this;
    }

    public ExtendParams xx() {
        addParam(XX);
        return this;
    }

    public byte[][] getByteParams(byte[]... args) {
        ArrayList<byte[]> byteParams = new ArrayList<>();
        Collections.addAll(byteParams, args);

        if (contains(NX)) {
            byteParams.add(SafeEncoder.encode(NX));
        }
        if (contains(XX)) {
            byteParams.add(SafeEncoder.encode(XX));
        }

        if (contains(EX)) {
            byteParams.add(SafeEncoder.encode(EX));
            byteParams.add(Protocol.toByteArray((int) getParam(EX)));
        }
        if (contains(PX)) {
            byteParams.add(SafeEncoder.encode(PX));
            byteParams.add(Protocol.toByteArray((long) getParam(PX)));
        }
        if (contains(VER)) {
            byteParams.add(SafeEncoder.encode(VER));
            byteParams.add(Protocol.toByteArray((int) getParam(VER)));
        }

        if (contains(ABS)) {
            byteParams.add(SafeEncoder.encode(ABS));
            byteParams.add(Protocol.toByteArray((int) getParam(ABS)));
        }

        return byteParams.toArray(new byte[byteParams.size()][]);
    }
}
