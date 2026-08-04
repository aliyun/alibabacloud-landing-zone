package com.aliyun.autowonder.redis;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.util.GzipCompressor;
import com.aliyun.autowonder.util.TranscoderUtil;
import org.apache.commons.codec.binary.Hex;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class AbstractRedisManager {

    String RET_OK = "OK";
    int SUCCESS = 1;
    int ALREADY_EXIST = 0;
    int VER_ERR = 0;
    int ERROR = -1;
    String EXCLUDE_PREFIX = "(";
    String POSITIVE_INFINITY = "+inf";
    String NEGATIVE_INFINITY = "-inf";

    private static final byte[] EMPTY_ARRAY = new byte[0];

    private static final String CHARSET = "UTF-8";

    private static final int COMPRESSION_THRESHOLD = 8192;
    private static final byte COMPRESSION_TYPE_NONE = '0';
    private static final byte COMPRESSION_TYPE_GZIP = '1';

    protected static final Charset UTF8 = StandardCharsets.UTF_8;

    public static final byte REDIS_STYPE_STRING = '1';
    public static final byte REDIS_STYPE_SERIALIZE_JDK = 'A';

    private static final ClassLoader customClassLoader = AbstractRedisManager.class.getClassLoader();


    public static byte[] serializeKey(Serializable object) {
        if (object instanceof String) {
            return ((String) object).getBytes(StandardCharsets.UTF_8);
        }
        return JSON.toJSONString(object).getBytes(StandardCharsets.UTF_8);
    }

    public static Object deserialize(byte[] bytes) {
        return decode(bytes, 0, bytes.length);
    }

    public static byte[] serialize(Object object) {
        return encode(object);
    }

    public static byte[] encode(Object object) {
        if (object == null) {
            return EMPTY_ARRAY;
        }
        if (object instanceof byte[]) {
            return (byte[]) object;
        }

        byte[] b;
        byte serializationFlag;

        if (object instanceof String) {
            b = TranscoderUtil.encodeString((String) object, CHARSET);
            serializationFlag = REDIS_STYPE_STRING;
        } else {
            b = TranscoderUtil.jdkSerialize(object);
            serializationFlag = REDIS_STYPE_SERIALIZE_JDK;
        }

        byte compressionFlag = COMPRESSION_TYPE_NONE;
        if (b.length > COMPRESSION_THRESHOLD) {
            b = GzipCompressor.compress(b);
            compressionFlag = COMPRESSION_TYPE_GZIP;
        }

        byte[] result = new byte[b.length + 7];
        byte[] fg = new byte[7];

        fg[0] = '_';
        fg[1] = 'i';
        fg[2] = 'd';
        fg[3] = 'l';
        fg[4] = serializationFlag;
        fg[5] = compressionFlag;
        fg[6] = '_';

        System.arraycopy(fg, 0, result, 0, 7);
        System.arraycopy(b, 0, result, 7, b.length);

        return result;
    }

    public static Object decode(byte[] data, int offset, int size) {
        if (data.length == 0) {
            return null;
        }

        if (isIdlRedisSerialize(data)) {
            byte[] realValue = new byte[size - 7];
            System.arraycopy(data, offset + 7, realValue, 0, size - 7);
            Object obj;

            byte serializationFlag = data[4];
            byte compressionFlag = data[5];

            if (compressionFlag != COMPRESSION_TYPE_GZIP && compressionFlag != COMPRESSION_TYPE_NONE) {
                throw new RuntimeException("unknown compression flag: " + compressionFlag);
            }
            if (compressionFlag == COMPRESSION_TYPE_GZIP) {
                realValue = GzipCompressor.decompress(realValue);
            }

            switch (serializationFlag) {
                case REDIS_STYPE_STRING:
                    obj = TranscoderUtil.decodeString(realValue, CHARSET);
                    break;
                case REDIS_STYPE_SERIALIZE_JDK:
                    obj = TranscoderUtil.jdkDeserialize(realValue, customClassLoader);
                    break;
                default:
                    throw new RuntimeException("unknown serialize flag: " + serializationFlag);
            }
            return obj;
        } else {
            return data;
        }
    }

    private static boolean isIdlRedisSerialize(byte[] data) {
        if (data[0] != '_') {
            return false;
        }
        if (data[1] != 'i') {
            return false;
        }
        if (data[2] != 'd') {
            return false;
        }
        if (data[3] != 'l') {
            return false;
        }
        return data[6] == '_';
    }

    private static boolean isJdkSerialize(byte[] data) {
        return data.length > 4 && "aced0005".equals(Hex.encodeHexString(new byte[]{data[0], data[1], data[2], data[3]}));
    }

    public static String stringifyKey(Serializable object) {
        if (object instanceof String) {
            return ((String) object);
        }
        return JSON.toJSONString(object);
    }
}
