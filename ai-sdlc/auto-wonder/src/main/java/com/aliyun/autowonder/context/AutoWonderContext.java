package com.aliyun.autowonder.context;


import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.org.OrgMemberDO;

public class AutoWonderContext {

    private AutoWonderContext() {}

    private static ThreadLocal<AutoWonderContext> threadLocalContext = ThreadLocal.withInitial(AutoWonderContext::new);

    private String requestId;

    private String traceId;

    private String operation;

    private Long userId;

    private Long currentOrgId;

    private OrgAccessLevel orgAccessLevel;

    private OrgMemberDO orgMember;

    private Boolean debug;

    private BizLog bizLog;

    private ResponseContext responseContext = new ResponseContext();

    public static AutoWonderContext get() {
        return threadLocalContext.get();
    }

    public static void destroy() {
        threadLocalContext.remove();
    }

    public static void setContext(AutoWonderContext context) {
        threadLocalContext.set(context);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public BizLog getBizLog() {
        return bizLog;
    }

    public void setBizLog(BizLog bizLog) {
        this.bizLog = bizLog;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getCurrentOrgId() {
        return currentOrgId;
    }

    public void setCurrentOrgId(Long currentOrgId) {
        this.currentOrgId = currentOrgId;
    }

    public OrgAccessLevel getOrgAccessLevel() {
        return orgAccessLevel;
    }

    public void setOrgAccessLevel(OrgAccessLevel orgAccessLevel) {
        this.orgAccessLevel = orgAccessLevel;
    }

    public OrgMemberDO getOrgMember() {
        return orgMember;
    }

    public void setOrgMember(OrgMemberDO orgMember) {
        this.orgMember = orgMember;
    }

    public void setDebug(String debug) {
        this.debug = Boolean.valueOf(debug);
    }

    public Boolean getDebug() {
        return debug;
    }

    public ResponseContext getResponseContext() {
        return responseContext;
    }
}
