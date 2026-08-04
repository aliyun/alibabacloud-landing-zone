package com.aliyun.autowonder.integration.provider;

import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ExternalWorkitemProvider {
    String provider();
    PageResult<ExternalWorkitemSummary> searchByIds(AoneOpenApiConfig config, String externalProjectId, List<String> ids);
    PageResult<ExternalWorkitemSummary> searchProject(AoneOpenApiConfig config, String externalProjectId, Date createdFrom, Date createdTo);
    PageResult<ExternalWorkitemSummary> searchProjectFirstPage(AoneOpenApiConfig config, String externalProjectId);
    ExternalWorkitemDetail getWorkitem(AoneOpenApiConfig config, String externalWorkitemId);
    List<ExternalIssueType> listEnabledIssueTypes(AoneOpenApiConfig config, String akProjectId, String staffId, String stamp);
    Map<String, List<ExternalStatusOption>> listOperationalStatuses(AoneOpenApiConfig config, String staffId, List<String> externalWorkitemIds);
    List<ExternalStatusOption> listStatusRules(AoneOpenApiConfig config, String akProjectId, int issueTypeId);
    List<ExternalComment> listComments(AoneOpenApiConfig config, List<String> externalWorkitemIds);
    ExternalComment createComment(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String contentMd);
    void updateStatus(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String statusName);
    void updateContent(AoneOpenApiConfig config, String externalWorkitemId, String staffId, String title, String contentMd);
}
