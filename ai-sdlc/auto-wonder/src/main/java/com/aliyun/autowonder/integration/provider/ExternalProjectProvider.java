package com.aliyun.autowonder.integration.provider;

import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;

import java.util.List;

public interface ExternalProjectProvider {
    PageResult<ExternalProject> searchProjects(AoneOpenApiConfig config, String query, int page, int pageSize);
    ExternalProject getProject(AoneOpenApiConfig config, String externalProjectId);
    List<ExternalProjectMember> listMembers(AoneOpenApiConfig config, String externalProjectId);
}
