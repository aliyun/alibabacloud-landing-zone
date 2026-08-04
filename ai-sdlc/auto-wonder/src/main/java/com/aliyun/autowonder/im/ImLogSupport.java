package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;

final class ImLogSupport {
    private ImLogSupport() {
    }

    static AlreadyLoggedException safeThrowable(Throwable failure) {
        return AlreadyLoggedException.from(failure);
    }
}
