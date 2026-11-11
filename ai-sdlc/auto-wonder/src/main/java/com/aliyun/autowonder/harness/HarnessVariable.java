package com.aliyun.autowonder.harness;

/** 模板变量：token 原样保留，客户端逐个填写；不适用需明确理由。 */
public record HarnessVariable(String token, String label, String requiredWhen) {
}
