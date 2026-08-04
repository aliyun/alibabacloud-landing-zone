package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.clarification.ClarificationDO;
import com.aliyun.autowonder.clarification.ClarificationDao;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import com.aliyun.autowonder.taskpackage.TeammateOutput;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoRelationDO;
import com.aliyun.autowonder.repo.RepoRelationDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageContextAssemblerTest {

    private WorkitemDao workitemDao;
    private ClarificationDao clarificationDao;
    private WorkitemCommentDao commentDao;
    private GuidanceDao guidanceDao;
    private SdlcStepDao stepDao;
    private AgentRepoPermDao repoPermDao;
    private AgentSkillDao skillDao;
    private SkillDao skillCatalogDao;
    private AgentMemoryRefDao memoryRefDao;
    private com.aliyun.autowonder.memory.MemoryDao memoryDao;
    private DispatchDao dispatchDao;
    private ArtifactDao artifactDao;
    private SquadMemberDao squadMemberDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private UserDao userDao;
    private StatusNodeDao statusNodeDao;
    private RepoDao repoDao;
    private RepoRelationDao repoRelationDao;
    private DispatchCheckpointService checkpointService;
    private PackageContextAssembler assembler;

    private static final long TENANT = 100L;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        clarificationDao = mock(ClarificationDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        guidanceDao = mock(GuidanceDao.class);
        stepDao = mock(SdlcStepDao.class);
        repoPermDao = mock(AgentRepoPermDao.class);
        skillDao = mock(AgentSkillDao.class);
        skillCatalogDao = mock(SkillDao.class);
        memoryRefDao = mock(AgentMemoryRefDao.class);
        memoryDao = mock(com.aliyun.autowonder.memory.MemoryDao.class);
        dispatchDao = mock(DispatchDao.class);
        artifactDao = mock(ArtifactDao.class);
        squadMemberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        userDao = mock(UserDao.class);
        statusNodeDao = mock(StatusNodeDao.class);
        repoDao = mock(RepoDao.class);
        repoRelationDao = mock(RepoRelationDao.class);
        checkpointService = mock(DispatchCheckpointService.class);
        assembler = new PackageContextAssembler(workitemDao, clarificationDao, commentDao, guidanceDao, stepDao,
                repoPermDao, skillDao, skillCatalogDao, memoryRefDao, memoryDao, dispatchDao, artifactDao,
                squadMemberDao, agentDao, agentVersionDao, userDao,
                repoDao, repoRelationDao, statusNodeDao, checkpointService);
    }

    @Test
    void downlinksAllInboundAndOutboundRelationsForBoundRepos() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        AgentRepoPermDO permission = new AgentRepoPermDO();
        permission.setTenantId(TENANT);
        permission.setAgentVersionId(401L);
        permission.setRepoId(10L);
        permission.setPermLevel("WRITE");
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of(permission));

        RepoDO bound = repo(10L, "service");
        RepoDO upstream = repo(11L, "contract");
        RepoDO downstream = repo(12L, "client");
        when(repoDao.findById(10L)).thenReturn(bound);
        when(repoDao.findById(11L)).thenReturn(upstream);
        when(repoDao.findById(12L)).thenReturn(downstream);

        RepoRelationDO inbound = relation(1L, 11L, 10L, "DEPENDS_ON");
        RepoRelationDO outbound = relation(2L, 10L, 12L, "PROVIDES_API_TO");
        when(repoRelationDao.listByRepoId(TENANT, 10L)).thenReturn(List.of(outbound, inbound));

        PackageContext context = assembler.assemble(dispatch(), version());

        assertEquals(List.of(10L), context.getRepoMap().get("boundRepoIds"));
        List<?> relations = (List<?>) context.getRepoMap().get("relations");
        assertEquals(2, relations.size());
        assertEquals("contract", ((Map<?, ?>) relations.get(0)).get("fromRepoName"));
        assertEquals("client", ((Map<?, ?>) relations.get(1)).get("toRepoName"));
    }

    @Test
    void downlinksEmptyRepoMapWhenWorkerHasBoundRepoWithoutRelations() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        AgentRepoPermDO permission = new AgentRepoPermDO();
        permission.setTenantId(TENANT);
        permission.setAgentVersionId(401L);
        permission.setRepoId(10L);
        permission.setPermLevel("READ");
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of(permission));
        when(repoDao.findById(10L)).thenReturn(repo(10L, "service"));
        when(repoRelationDao.listByRepoId(TENANT, 10L)).thenReturn(List.of());

        PackageContext context = assembler.assemble(dispatch(), version());

        assertNotNull(context.getRepoMap());
        assertEquals(List.of(), context.getRepoMap().get("relations"));
    }

    @Test
    void resolvesBoundCapabilitiesFromTheTenantCatalog() {
        AgentSkillDO binding = new AgentSkillDO();
        binding.setTenantId(TENANT);
        binding.setAgentVersionId(401L);
        binding.setSkillId(91L);
        SkillDO capability = new SkillDO();
        capability.setId(91L);
        capability.setTenantId(TENANT);
        capability.setType("MCP");
        capability.setName("repo-tools");
        capability.setInstallSpec("{\"transport\":\"http\",\"url\":\"https://mcp.example.test/api\",\"authType\":\"none\"}");
        capability.setVersion(4);
        when(skillDao.listByVersion(401L)).thenReturn(List.of(binding));
        when(skillCatalogDao.findById(91L)).thenReturn(capability);

        List<Map<String, Object>> resolved = PackageContextAssembler.buildCapabilities(
                skillDao, skillCatalogDao, TENANT, 401L);

        assertEquals(1, resolved.size());
        assertEquals("MCP", resolved.get(0).get("type"));
        assertEquals("repo-tools", resolved.get(0).get("name"));
        assertEquals(4, resolved.get(0).get("version"));
        assertEquals("https://mcp.example.test/api",
                ((Map<?, ?>) resolved.get(0).get("config")).get("url"));
    }

    @Test
    void ignores_stale_binding_after_skill_is_deleted() {
        AgentSkillDO binding = new AgentSkillDO();
        binding.setTenantId(TENANT);
        binding.setAgentVersionId(401L);
        binding.setSkillId(92L);
        when(skillDao.listByVersion(401L)).thenReturn(List.of(binding));
        when(skillCatalogDao.findById(92L)).thenReturn(null);

        List<Map<String, Object>> resolved = PackageContextAssembler.buildCapabilities(
                skillDao, skillCatalogDao, TENANT, 401L);

        assertTrue(resolved.isEmpty());
    }

    @Test
    void includesAllTenantCommentsAsSharedWorkerContext() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        WorkitemCommentDO first = new WorkitemCommentDO();
        first.setId(10L); first.setTenantId(TENANT); first.setAuthorType("HUMAN"); first.setAuthorRef(7L);
        first.setContentMd("@Dev adjust the API");
        WorkitemCommentDO second = new WorkitemCommentDO();
        second.setId(11L); second.setTenantId(TENANT); second.setAuthorType("AGENT"); second.setAuthorRef(400L);
        second.setContentMd("I have started the adjustment.");
        when(commentDao.listByWorkitem(200L)).thenReturn(List.of(first, second));

        PackageContext context = assembler.assemble(dispatch(), version());

        assertTrue(context.getCommentsMd().contains("Comment 10"));
        assertTrue(context.getCommentsMd().contains("@Dev adjust the API"));
        assertTrue(context.getCommentsMd().contains("I have started the adjustment."));
    }

    @Test
    void sideInteractionReworkCarriesTheSideConversationIntoFormalContext() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO rework = dispatch();
        rework.setResumeMode("COMMENT_REWORK");
        rework.setIdempotencyKey("interaction-rework:700");

        DispatchDO olderSide = dispatch();
        olderSide.setId(690L);
        olderSide.setResumeMode("SIDE_INTERACTION");
        olderSide.setResumeFromDispatchId(650L);
        DispatchDO unrelatedSide = dispatch();
        unrelatedSide.setId(695L);
        unrelatedSide.setResumeMode("SIDE_INTERACTION");
        unrelatedSide.setResumeFromDispatchId(660L);
        DispatchDO sourceSide = dispatch();
        sourceSide.setId(700L);
        sourceSide.setResumeMode("SIDE_INTERACTION");
        sourceSide.setResumeFromDispatchId(650L);
        sourceSide.setIdempotencyKey("guidance:2");
        when(dispatchDao.findById(690L)).thenReturn(olderSide);
        when(dispatchDao.findById(695L)).thenReturn(unrelatedSide);
        when(dispatchDao.findById(700L)).thenReturn(sourceSide);

        GuidanceDO older = new GuidanceDO();
        older.setId(1L);
        older.setTenantId(TENANT);
        older.setWorkitemId(200L);
        older.setTargetAgentId(400L);
        older.setDispatchId(690L);
        older.setCommentId(10L);
        older.setReplyCommentId(11L);
        GuidanceDO trigger = new GuidanceDO();
        trigger.setId(2L);
        trigger.setTenantId(TENANT);
        trigger.setWorkitemId(200L);
        trigger.setTargetAgentId(400L);
        trigger.setDispatchId(700L);
        trigger.setCommentId(20L);
        GuidanceDO unrelated = new GuidanceDO();
        unrelated.setId(3L);
        unrelated.setTenantId(TENANT);
        unrelated.setWorkitemId(200L);
        unrelated.setTargetAgentId(400L);
        unrelated.setDispatchId(695L);
        unrelated.setCommentId(30L);
        when(guidanceDao.findById(2L)).thenReturn(trigger);
        when(guidanceDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(older, unrelated, trigger));

        WorkitemCommentDO firstQuestion = comment(10L, "图应该固定角色吗？", "HUMAN", 10004L);
        WorkitemCommentDO firstReply = comment(11L, "是，角色固定，流转用箭头。", "AGENT", 400L);
        WorkitemCommentDO start = comment(20L, "好的，开始", "HUMAN", 10004L);
        WorkitemCommentDO unrelatedQuestion = comment(30L, "另一个问题", "HUMAN", 10004L);
        when(commentDao.findById(TENANT, 10L)).thenReturn(firstQuestion);
        when(commentDao.findById(TENANT, 11L)).thenReturn(firstReply);
        when(commentDao.findById(TENANT, 20L)).thenReturn(start);
        when(commentDao.findById(TENANT, 30L)).thenReturn(unrelatedQuestion);

        PackageContext context = assembler.assemble(rework, version());

        assertTrue(context.getInteractionContextMd().contains("图应该固定角色吗？"));
        assertTrue(context.getInteractionContextMd().contains("是，角色固定，流转用箭头。"));
        assertTrue(context.getInteractionContextMd().contains("好的，开始"));
        assertFalse(context.getInteractionContextMd().contains("另一个问题"));
    }

    @Test
    void canonicalInteractionReworkDoesNotDuplicateConversationContext() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO rework = dispatch();
        rework.setResumeMode("COMMENT_REWORK");
        rework.setIdempotencyKey("interaction-rework:700");
        DispatchDO sourceMain = dispatch();
        sourceMain.setId(700L);
        sourceMain.setResumeMode("CANONICAL_INTERACTION");
        sourceMain.setIdempotencyKey("guidance:2");
        when(dispatchDao.findById(700L)).thenReturn(sourceMain);

        GuidanceDO trigger = new GuidanceDO();
        trigger.setId(2L);
        trigger.setTenantId(TENANT);
        trigger.setWorkitemId(200L);
        trigger.setTargetAgentId(400L);
        trigger.setDispatchId(700L);
        trigger.setCommentId(20L);
        when(guidanceDao.findById(2L)).thenReturn(trigger);
        when(guidanceDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(trigger));
        when(commentDao.findById(TENANT, 20L))
                .thenReturn(comment(20L, "开始", "HUMAN", 10004L));

        PackageContext context = assembler.assemble(rework, version());

        assertNull(context.getInteractionContextMd());
    }

    private DispatchDO dispatch() {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setAgentId(400L);
        d.setAgentVersionId(401L);
        return d;
    }

    private WorkitemCommentDO comment(long id, String content, String authorType, long authorRef) {
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setId(id);
        comment.setTenantId(TENANT);
        comment.setWorkitemId(200L);
        comment.setAuthorType(authorType);
        comment.setAuthorRef(authorRef);
        comment.setContentMd(content);
        return comment;
    }

    private AgentVersionDO version() {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(401L);
        v.setTenantId(TENANT);
        v.setAgentId(400L);
        v.setRoleName("Backend Engineer");
        v.setRoleCode("BE");
        v.setResponsibilities("code");
        v.setIdentityJson("{\"name\":\"Bob\"}");
        return v;
    }

    private RepoDO repo(long id, String name) {
        RepoDO repo = new RepoDO();
        repo.setId(id);
        repo.setTenantId(TENANT);
        repo.setName(name);
        repo.setUrl("git@example.test/" + name + ".git");
        return repo;
    }

    private RepoRelationDO relation(long id, long fromRepoId, long toRepoId, String type) {
        RepoRelationDO relation = new RepoRelationDO();
        relation.setId(id);
        relation.setTenantId(TENANT);
        relation.setFromRepoId(fromRepoId);
        relation.setToRepoId(toRepoId);
        relation.setRelationType(type);
        return relation;
    }

    private SdlcStepDO workflowStep(long id, long sdlcId, int order, String name, String kind,
            String instruction, String checklistJson, String gatePolicyJson) {
        SdlcStepDO step = new SdlcStepDO();
        step.setId(id);
        step.setTenantId(TENANT);
        step.setSdlcId(sdlcId);
        step.setStepOrder(order);
        step.setName(name);
        step.setKind(kind);
        step.setInstructionMd(instruction);
        step.setChecklistJson(checklistJson);
        step.setGatePolicyJson(gatePolicyJson);
        step.setRequired(true);
        return step;
    }

    @Test
    void assemblesCoreFields() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("Build X");
        w.setContentMd("do it");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        SdlcStepDO step = new SdlcStepDO();
        step.setId(300L);
        step.setTenantId(TENANT);
        step.setOnSuccess("{\"action\":\"END\"}");
        step.setOnFail("{\"action\":\"RETRY\",\"maxAttempts\":2}");
        step.setHandlerType("AGENT");
        when(stepDao.findById(300L)).thenReturn(step);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());

        assertEquals(TENANT, ctx.getTenantId());
        assertEquals(500L, ctx.getDispatchId());
        assertEquals(200L, ctx.getWorkitemId());
        assertEquals(400L, ctx.getAgentId());
        assertEquals(300L, ctx.getSdlcStepId());
        assertEquals("Build X", ctx.getWorkitemTitle());
        assertEquals("do it", ctx.getWorkitemContentMd());
        assertNull(ctx.getClarificationMd());
        assertNotNull(ctx.getIdentity());
        assertNotNull(ctx.getSdlc());
        assertTrue(ctx.getTeammates() == null || ctx.getTeammates().isEmpty());
    }

    @Test
    void assemblesRequirementDocumentRefsFromWorkitemArtifacts() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(w);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        ArtifactDO doc = new ArtifactDO();
        doc.setTenantId(TENANT);
        doc.setWorkitemId(200L);
        doc.setName("requirements/spec.md");
        doc.setOssRef("artifact-bucket/t/100/workitem/200/requirements/spec.md");
        when(artifactDao.listByWorkitemAndType(TENANT, 200L, RequirementDocumentService.TYPE))
                .thenReturn(List.of(doc));

        PackageContext ctx = assembler.assemble(dispatch(), version());

        assertEquals(1, ctx.getRequirementDocuments().size());
        assertEquals("requirements/spec.md", ctx.getRequirementDocuments().get(0).getName());
        assertEquals("artifact-bucket/t/100/workitem/200/requirements/spec.md",
                ctx.getRequirementDocuments().get(0).getOssRef());
    }

    @Test
    void writableReposDoNotReceiveServerGeneratedWorktreeBranch() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        workitem.setTitle("[E2E] 浏览器标签页 favicon 改为阿里云 Logo");
        when(workitemDao.findById(200L)).thenReturn(workitem);
        AgentRepoPermDO write = new AgentRepoPermDO();
        write.setTenantId(TENANT);
        write.setRepoId(700L);
        write.setPermLevel("WRITE");
        AgentRepoPermDO read = new AgentRepoPermDO();
        read.setTenantId(TENANT);
        read.setRepoId(701L);
        read.setPermLevel("READ");
        RepoDO writeRepo = new RepoDO();
        writeRepo.setId(700L);
        writeRepo.setTenantId(TENANT);
        writeRepo.setName("write-repo");
        writeRepo.setUrl("git@example/write.git");
        RepoDO readRepo = new RepoDO();
        readRepo.setId(701L);
        readRepo.setTenantId(TENANT);
        readRepo.setName("read-repo");
        readRepo.setUrl("git@example/read.git");
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of(write, read));
        when(repoDao.findById(700L)).thenReturn(writeRepo);
        when(repoDao.findById(701L)).thenReturn(readRepo);
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());

        assertEquals("eager", ctx.getRepos().get(0).get("mode"));
        assertEquals("lazy", ctx.getRepos().get(1).get("mode"));
        assertFalse(ctx.getRepos().get(0).containsKey("worktreeBranch"));
        assertFalse(ctx.getRepos().get(1).containsKey("worktreeBranch"));
    }

    @Test
    void repoWithoutConfiguredDefaultBranchOmitsRef() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);

        AgentRepoPermDO permission = new AgentRepoPermDO();
        permission.setTenantId(TENANT);
        permission.setRepoId(700L);
        permission.setPermLevel("WRITE");
        RepoDO repo = new RepoDO();
        repo.setId(700L);
        repo.setTenantId(TENANT);
        repo.setName("daily-tasks");
        repo.setUrl("git@example/daily-tasks.git");
        repo.setDefaultBranch(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of(permission));
        when(repoDao.findById(700L)).thenReturn(repo);
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());

        assertFalse(ctx.getRepos().get(0).containsKey("ref"));
    }

    @Test
    void reposCarryNoCredentialsSoExecutorUsesLocalGitPermission() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(200L);
        workitem.setTenantId(TENANT);
        when(workitemDao.findById(200L)).thenReturn(workitem);

        AgentRepoPermDO permission = new AgentRepoPermDO();
        permission.setTenantId(TENANT);
        permission.setRepoId(700L);
        permission.setPermLevel("WRITE");
        RepoDO repo = new RepoDO();
        repo.setId(700L);
        repo.setTenantId(TENANT);
        repo.setName("auto-wonder");
        repo.setUrl("git@example/auto-wonder.git");
        repo.setDefaultBranch("master");
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of(permission));
        when(repoDao.findById(700L)).thenReturn(repo);
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());

        Map<String, Object> entry = ctx.getRepos().get(0);
        assertFalse(entry.containsKey("authType"));
        assertFalse(entry.containsKey("sshPrivateKey"));
        assertEquals("git@example/auto-wonder.git", entry.get("url"));
        assertEquals("master", entry.get("ref"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildsClientWorkflowSdlcFromAllSteps() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("Build X");
        w.setContentMd("do it");
        w.setSdlcId(900L);
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());

        SdlcStepDO current = workflowStep(300L, 900L, 1, "需求理解", "analysis",
                "阅读需求并判断是否满足。", "[\"确认范围\",\"识别风险\"]",
                "{\"requiresReview\":true,\"requiredEvidence\":[{\"kind\":\"CODE_REVIEW\",\"acceptedStates\":[\"OPEN\"]}],"
                        + "\"gitDelivery\":{\"scope\":\"CHANGED\",\"branch\":\"CURRENT\",\"requireRemoteHead\":true}}");
        SdlcStepDO next = workflowStep(301L, 900L, 2, "编码实现", "implementation",
                "基于 worktree 完成实现。", null, null);
        SdlcStepDO crossTenant = workflowStep(302L, 900L, 3, "泄露", "bad", "bad", null, null);
        crossTenant.setTenantId(999L);
        when(stepDao.findById(300L)).thenReturn(current);
        when(stepDao.listBySdlc(900L)).thenReturn(List.of(current, next, crossTenant));

        PackageContext ctx = assembler.assemble(dispatch(), version());

        Map<String, Object> sdlc = ctx.getSdlc();
        assertEquals("900", sdlc.get("sdlcId"));
        assertEquals("300", sdlc.get("currentStepId"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) sdlc.get("steps");
        assertEquals(2, steps.size());
        assertEquals("300", steps.get(0).get("id"));
        assertEquals("需求理解", steps.get(0).get("name"));
        assertEquals("analysis", steps.get(0).get("kind"));
        assertEquals(true, steps.get(0).get("required"));
        assertEquals("阅读需求并判断是否满足。", steps.get(0).get("instruction"));
        assertEquals(List.of(
                Map.of("id", "cl_0", "text", "确认范围", "checked", false),
                Map.of("id", "cl_1", "text", "识别风险", "checked", false)),
                steps.get(0).get("checklist"));
        assertEquals(true, ((Map<String, Object>) steps.get(0).get("gatePolicy")).get("requiresReview"));
        List<Map<String, Object>> requiredEvidence = (List<Map<String, Object>>)
                ((Map<String, Object>) steps.get(0).get("gatePolicy")).get("requiredEvidence");
        assertEquals("CODE_REVIEW", requiredEvidence.get(0).get("kind"));
        Map<String, Object> gitDelivery = (Map<String, Object>)
                ((Map<String, Object>) steps.get(0).get("gatePolicy")).get("gitDelivery");
        assertEquals("CHANGED", gitDelivery.get("scope"));
        assertEquals("CURRENT", gitDelivery.get("branch"));
        Map<String, Object> outputContract = (Map<String, Object>) sdlc.get("outputContract");
        assertEquals(true, outputContract.get("reviewerHandoffRequired"));
        assertFalse(steps.get(0).containsKey("handlerType"));
        assertFalse(steps.get(0).containsKey("onSuccess"));
        assertFalse(steps.get(0).containsKey("onFail"));
    }

    @Test
    void includesClarificationWhenPresent() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        ClarificationDO c = new ClarificationDO();
        c.setTenantId(TENANT);
        c.setContentMd("please clarify");
        when(clarificationDao.findByWorkitem(200L)).thenReturn(c);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        assertEquals("please clarify", ctx.getClarificationMd());
    }

    @Test
    void crossTenantClarificationIsIgnored() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        ClarificationDO c = new ClarificationDO();
        c.setTenantId(999L); // different tenant
        c.setContentMd("leak");
        when(clarificationDao.findByWorkitem(200L)).thenReturn(c);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        assertNull(ctx.getClarificationMd());
    }

    @Test
    void buildsTeammatesOnlyFromDirectHandoffSource() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setIdempotencyKey("handoff:600");

        DispatchDO mate = new DispatchDO();
        mate.setId(600L);
        mate.setTenantId(TENANT);
        mate.setWorkitemId(200L);
        mate.setAgentId(700L);
        mate.setStatus("SUCCEEDED");
        mate.setResultSummary("PM conclusion");
        when(dispatchDao.findById(600L)).thenReturn(mate);

        ArtifactDO art = new ArtifactDO();
        art.setTenantId(TENANT);
        art.setName("report.md");
        art.setOssRef("oss://b/report.md");
        ArtifactDO rejected = artifact("artifacts/attempts/coding/attempt-1/evidence/rejected.log", "oss://b/rejected.log");
        ArtifactDO trace = artifact("artifacts/output/observability/trace.json", "oss://b/trace.json");
        ArtifactDO result = artifact("artifacts/output/result/runtime-result.json", "oss://b/result.json");
        ArtifactDO delta = artifact("artifacts/output/learning_delta/evolution_delta.json", "oss://b/delta.json");
        ArtifactDO metadata = artifact("artifacts/output/handoff/metadata.json", "oss://b/metadata.json");
        ArtifactDO summary = artifact("artifacts/output/handoff/summary.md", "oss://b/summary.md");
        ArtifactDO revision = artifact("artifacts/output/deliverables/runtime-source-revision.json", "oss://b/revision.json");
        ArtifactDO empty = artifact("artifacts/output/deliverables/empty.md", "oss://b/empty.md");
        empty.setSize(0L);
        ArtifactDO evidence = artifact("artifacts/output/evidence/tests.log", "oss://b/tests.log");
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of(
                rejected, trace, result, delta, metadata, summary, revision, empty, art, evidence));

        PackageContext ctx = assembler.assemble(current, version());
        assertEquals(600L, ctx.getSourceDispatchId());
        assertEquals(1, ctx.getTeammates().size());
        TeammateOutput t = ctx.getTeammates().get(0);
        assertEquals("700", t.getAgentId());
        assertEquals("PM conclusion", t.getConclusionMd());
        assertEquals(2, t.getArtifacts().size());
        assertEquals("report.md", t.getArtifacts().get(0).getName());
        assertEquals("artifacts/output/evidence/tests.log", t.getArtifacts().get(1).getName());
        verify(dispatchDao, never()).listSucceededByWorkitem(anyLong(), anyLong());
    }

    @Test
    void deliverySourceIsIndependentFromNewWorkerSessionResumeSource() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setResumeMode("COMMENT_REWORK");
        current.setIdempotencyKey("interaction-rework:700");
        current.setResumeFromDispatchId(650L);
        current.setDeliverySourceDispatchId(600L);

        GuidanceDO trigger = new GuidanceDO();
        trigger.setId(2L); trigger.setDispatchId(700L); trigger.setCommentId(21L);
        when(guidanceDao.listByWorkitem(TENANT, 200L)).thenReturn(List.of(trigger));
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setId(21L); comment.setTenantId(TENANT); comment.setWorkitemId(200L);
        comment.setAuthorType("HUMAN"); comment.setAuthorRef(10004L);
        comment.setContentMd("请重新处理");
        when(commentDao.findById(TENANT, 21L)).thenReturn(comment);

        DispatchDO previousWorker = new DispatchDO();
        previousWorker.setId(600L);
        previousWorker.setTenantId(TENANT);
        previousWorker.setWorkitemId(200L);
        previousWorker.setAgentId(399L);
        previousWorker.setStatus("SUCCEEDED");
        previousWorker.setResultSummary("previous worker handoff");
        when(dispatchDao.findById(600L)).thenReturn(previousWorker);
        ArtifactDO artifact = artifact("artifacts/output/handoff.md", "oss://b/handoff.md");
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of(artifact));

        PackageContext ctx = assembler.assemble(current, version());

        assertEquals(600L, ctx.getSourceDispatchId());
        assertEquals("previous worker handoff", ctx.getTeammates().get(0).getConclusionMd());
        assertEquals("oss://b/handoff.md", ctx.getTeammates().get(0).getArtifacts().get(0).getOssRef());
        verify(dispatchDao, never()).findById(650L);
    }

    @Test
    void recoveryDispatchUsesInterruptedDispatchAsDirectSource() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setIdempotencyKey("continue:600");
        DispatchDO source = new DispatchDO();
        source.setId(600L);
        source.setTenantId(TENANT);
        source.setWorkitemId(200L);
        source.setAgentId(400L);
        source.setStatus("FAILED");
        source.setResultSummary("interrupted after code changes");
        when(dispatchDao.findById(600L)).thenReturn(source);
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(current, version());
        assertEquals(600L, ctx.getSourceDispatchId());
        assertEquals(1, ctx.getTeammates().size());
        assertEquals("interrupted after code changes", ctx.getTeammates().get(0).getConclusionMd());
    }

    @Test
    void carriesDeliveryRevisionThroughExplicitReworkSourceChain() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setIdempotencyKey("continue:600");
        DispatchDO failedRework = sourceDispatch(600L, "FAILED", "continue:550");
        DispatchDO review = sourceDispatch(550L, "SUCCEEDED", "handoff:450");
        DispatchDO delivery = sourceDispatch(450L, "SUCCEEDED", null);
        when(dispatchDao.findById(600L)).thenReturn(failedRework);
        when(dispatchDao.findById(550L)).thenReturn(review);
        when(dispatchDao.findById(450L)).thenReturn(delivery);

        ArtifactDO revision = artifact("artifacts/output/deliverables/runtime-source-revision.json",
                "oss://b/runtime-source-revision.json");
        ArtifactDO unrelated = artifact("artifacts/output/evidence/full.log", "oss://b/full.log");
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of());
        when(artifactDao.listByDispatch(TENANT, 550L)).thenReturn(List.of());
        when(artifactDao.listByDispatch(TENANT, 450L)).thenReturn(List.of(unrelated, revision));

        PackageContext ctx = assembler.assemble(current, version());

        assertEquals(600L, ctx.getSourceDispatchId());
        assertEquals(1, ctx.getSourceRevisionArtifacts().size());
        assertEquals(revision.getOssRef(), ctx.getSourceRevisionArtifacts().get(0).getOssRef());
        assertTrue(ctx.getTeammates().get(0).getArtifacts().isEmpty(),
                "ancestor artifacts must not become direct predecessor context");
    }

    @Test
    void carriesCheckpointRevisionWhenReworkChainHasNoDeliveryArtifact() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setIdempotencyKey("continue:600");
        DispatchDO failedSource = sourceDispatch(600L, "FAILED", null);
        when(dispatchDao.findById(600L)).thenReturn(failedSource);
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of());
        TaskArtifactRef checkpointRevision = new TaskArtifactRef();
        checkpointRevision.setName("checkpoint/7/deliverables/runtime-source-revision.json");
        checkpointRevision.setOssRef("oss://checkpoint/repo-state.json");
        when(checkpointService.findRepoRevisionArtifact(TENANT, 600L))
                .thenReturn(checkpointRevision);

        PackageContext ctx = assembler.assemble(current, version());

        assertEquals(List.of(checkpointRevision), ctx.getSourceRevisionArtifacts());
    }

    @Test
    void recoveryPrefersCheckpointBaselineOverDirectLocalRuntimeRevision() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setResumeMode("RECOVERY");
        current.setResumeFromDispatchId(600L);
        current.setIdempotencyKey("continue:600");
        DispatchDO pausedSource = sourceDispatch(600L, "PAUSED", null);
        when(dispatchDao.findById(600L)).thenReturn(pausedSource);

        ArtifactDO localRevision = artifact(
                "artifacts/output/deliverables/runtime-source-revision.json",
                "oss://result/local-head.json");
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of(localRevision));
        TaskArtifactRef checkpointBaseline = new TaskArtifactRef();
        checkpointBaseline.setName("checkpoint/7/deliverables/runtime-source-revision.json");
        checkpointBaseline.setOssRef("oss://checkpoint/materialization-base.json");
        when(checkpointService.findRepoRevisionArtifact(TENANT, 600L))
                .thenReturn(checkpointBaseline);

        PackageContext ctx = assembler.assemble(current, version());

        assertEquals(2, ctx.getSourceRevisionArtifacts().size());
        assertEquals(checkpointBaseline.getOssRef(),
                ctx.getSourceRevisionArtifacts().get(0).getOssRef(),
                "checkpoint baseline must win before the local-only runtime HEAD");
        assertEquals(localRevision.getOssRef(),
                ctx.getSourceRevisionArtifacts().get(1).getOssRef());
    }

    @Test
    void checkpointLookupFailureDoesNotDiscardDeliveryRevision() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        DispatchDO current = dispatch();
        current.setIdempotencyKey("continue:600");
        when(dispatchDao.findById(600L)).thenReturn(sourceDispatch(600L, "FAILED", null));
        ArtifactDO revision = artifact("artifacts/output/deliverables/runtime-source-revision.json",
                "oss://delivery/revision.json");
        when(artifactDao.listByDispatch(TENANT, 600L)).thenReturn(List.of(revision));
        when(checkpointService.findRepoRevisionArtifact(TENANT, 600L))
                .thenThrow(new IllegalStateException("checkpoint store unavailable"));

        PackageContext ctx = assertDoesNotThrow(() -> assembler.assemble(current, version()));

        assertEquals(1, ctx.getSourceRevisionArtifacts().size());
        assertEquals(revision.getOssRef(), ctx.getSourceRevisionArtifacts().get(0).getOssRef());
    }

    @Test
    void ignoresInvalidOrNonHandoffSourceInsteadOfFallingBackToHistory() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());

        DispatchDO current = dispatch();
        current.setIdempotencyKey("manual:600");

        DispatchDO historical = new DispatchDO();
        historical.setId(599L);
        historical.setTenantId(TENANT);
        historical.setWorkitemId(200L);
        historical.setAgentId(400L);
        historical.setStatus("SUCCEEDED");
        historical.setResultSummary("historical delivery");
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of(historical));

        PackageContext ctx = assembler.assemble(current, version());
        assertNull(ctx.getSourceDispatchId());
        assertTrue(ctx.getTeammates() == null || ctx.getTeammates().isEmpty());
        verify(dispatchDao, never()).listSucceededByWorkitem(anyLong(), anyLong());
    }

    private DispatchDO sourceDispatch(long id, String status, String idempotencyKey) {
        DispatchDO source = new DispatchDO();
        source.setId(id);
        source.setTenantId(TENANT);
        source.setWorkitemId(200L);
        source.setAgentId(400L);
        source.setStatus(status);
        source.setIdempotencyKey(idempotencyKey);
        return source;
    }

    private ArtifactDO artifact(String name, String ossRef) {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setTenantId(TENANT);
        artifact.setName(name);
        artifact.setOssRef(ossRef);
        return artifact;
    }

    private SquadMemberDO member(long id, long squadId, Long agentId) {
        SquadMemberDO m = new SquadMemberDO();
        m.setId(id);
        m.setTenantId(TENANT);
        m.setSquadId(squadId);
        m.setAgentId(agentId);
        return m;
    }

    private void stubEmptyExceptRoster() {
        when(clarificationDao.findByWorkitem(200L)).thenReturn(null);
        when(stepDao.findById(300L)).thenReturn(null);
        when(repoPermDao.listByVersion(401L)).thenReturn(List.of());
        when(skillDao.listByVersion(401L)).thenReturn(List.of());
        when(memoryRefDao.listByVersion(401L)).thenReturn(List.of());
        when(dispatchDao.listSucceededByWorkitem(TENANT, 200L)).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterDigitalTeammatesDedupAndExcludeSelfAcrossSquads() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L); w.setTenantId(TENANT); w.setTitle("t"); w.setContentMd("c");
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();

        // acting agent (400) belongs to two squads
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of(
                member(1L, 10L, 400L),
                member(2L, 20L, 400L)));
        // squad 10: self (400) + teammate 700
        when(squadMemberDao.listBySquad(10L)).thenReturn(List.of(
                member(3L, 10L, 400L),
                member(4L, 10L, 700L)));
        // squad 20: teammate 700 again (dup) + teammate 800
        when(squadMemberDao.listBySquad(20L)).thenReturn(List.of(
                member(5L, 20L, 700L),
                member(6L, 20L, 800L)));

        AgentDO a700 = new AgentDO();
        a700.setId(700L); a700.setTenantId(TENANT); a700.setOnlineVersionId(701L);
        AgentDO a800 = new AgentDO();
        a800.setId(800L); a800.setTenantId(TENANT); a800.setOnlineVersionId(801L);
        when(agentDao.findById(700L)).thenReturn(a700);
        when(agentDao.findById(800L)).thenReturn(a800);

        AgentVersionDO v700 = new AgentVersionDO();
        v700.setId(701L); v700.setTenantId(TENANT); v700.setRoleCode("PM"); v700.setRoleName("Product Manager");
        AgentVersionDO v800 = new AgentVersionDO();
        v800.setId(801L); v800.setTenantId(TENANT); v800.setRoleCode("QA"); v800.setRoleName("Tester");
        when(agentVersionDao.findById(701L)).thenReturn(v700);
        when(agentVersionDao.findById(801L)).thenReturn(v800);

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        assertNotNull(roster);
        List<Map<String, Object>> digital = (List<Map<String, Object>>) roster.get("digitalTeammates");
        // self (400) excluded, 700 deduped -> exactly 700 and 800
        assertEquals(2, digital.size());
        assertEquals(700L, digital.get(0).get("agentId"));
        assertEquals("PM", digital.get(0).get("roleCode"));
        assertEquals("Product Manager", digital.get(0).get("roleName"));
        assertEquals(800L, digital.get(1).get("agentId"));
        assertEquals("QA", digital.get(1).get("roleCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterExcludesCrossTenantWorkitemHumanAssignee() {
        // workitem belongs to a DIFFERENT tenant than the dispatch
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(999L); // cross-tenant
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(12345L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        assertNotNull(roster);
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertTrue(humans == null || humans.isEmpty(),
                "cross-tenant workitem HUMAN assignee must not leak into roster");
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterIncludesSameTenantHumanAssignee() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(12345L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertEquals(1, humans.size());
        assertEquals(12345L, humans.get(0).get("userId"));
        assertEquals("assignee", humans.get(0).get("relation"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterHumanTeammateIncludesDisplayName() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(42L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        UserDO user = new UserDO();
        user.setId(42L);
        user.setNickname("Alice Wang");
        when(userDao.findById(42L)).thenReturn(user);

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertEquals(1, humans.size());
        assertEquals(42L, humans.get(0).get("userId"));
        assertEquals("Alice Wang", humans.get(0).get("name"));
        assertEquals("assignee", humans.get(0).get("relation"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterHumanNameFallsBackToUsernameWhenNoNickname() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(43L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        UserDO user = new UserDO();
        user.setId(43L);
        user.setUsername("bob_dev");
        when(userDao.findById(43L)).thenReturn(user);

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertEquals(1, humans.size());
        assertEquals("bob_dev", humans.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterIncludesAssignOperatorEvenWhenAssigneeIsAgent() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("AGENT");
        w.setAssigneeRef(10002L);
        w.setAssignOperatorId(42L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        UserDO user = new UserDO();
        user.setId(42L);
        user.setNickname("Zhang");
        when(userDao.findById(42L)).thenReturn(user);

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertTrue(humans.stream().anyMatch(h ->
                        Long.valueOf(42L).equals(h.get("userId"))
                                && "指派操作人".equals(h.get("relation"))
                                && "需求决策人".equals(h.get("role"))
                                && "Zhang".equals(h.get("name"))),
                "roster must include the assign-operator as 需求决策人 even when assignee is an AGENT");
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterDoesNotDuplicateOperatorWhenSameAsHumanAssignee() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(42L);
        w.setAssignOperatorId(42L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertEquals(1, humans.size());
        assertEquals("指派操作人", humans.get(0).get("relation"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rosterHumanNameNullWhenUserNotFound() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(44L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());
        when(userDao.findById(44L)).thenReturn(null);

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> roster = ctx.getRoster();
        List<Map<String, Object>> humans = (List<Map<String, Object>>) roster.get("humanTeammates");
        assertEquals(1, humans.size());
        assertNull(humans.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void workitemStatusIncludesCurrentAndAllStatuses() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setTemplateId(50L);
        w.setStatusNodeId(51L);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        StatusNodeDO current = new StatusNodeDO();
        current.setId(51L); current.setCode("developing"); current.setName("开发中"); current.setCategory("IN_PROGRESS");
        when(statusNodeDao.findById(51L)).thenReturn(current);

        StatusNodeDO n1 = new StatusNodeDO();
        n1.setId(50L); n1.setCode("new"); n1.setName("新建"); n1.setCategory("INIT");
        StatusNodeDO n2 = current;
        StatusNodeDO n3 = new StatusNodeDO();
        n3.setId(52L); n3.setCode("released"); n3.setName("已发布"); n3.setCategory("DONE");
        when(statusNodeDao.listByTemplateId(50L)).thenReturn(List.of(n1, n2, n3));

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> ws = ctx.getWorkitemStatus();
        assertNotNull(ws);

        Map<String, Object> cur = (Map<String, Object>) ws.get("currentStatus");
        assertEquals(51L, cur.get("nodeId"));
        assertEquals("developing", cur.get("code"));
        assertEquals("开发中", cur.get("name"));
        assertEquals("IN_PROGRESS", cur.get("category"));

        List<Map<String, Object>> statuses = (List<Map<String, Object>>) ws.get("statuses");
        assertEquals(3, statuses.size());
        assertEquals("new", statuses.get(0).get("code"));
        assertEquals("developing", statuses.get(1).get("code"));
        assertEquals("released", statuses.get(2).get("code"));
    }

    @Test
    void workitemStatusEmptyWhenNoTemplate() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setTitle("t"); w.setContentMd("c");
        w.setTemplateId(null);
        when(workitemDao.findById(200L)).thenReturn(w);
        stubEmptyExceptRoster();
        when(squadMemberDao.listByAgent(400L)).thenReturn(List.of());

        PackageContext ctx = assembler.assemble(dispatch(), version());
        Map<String, Object> ws = ctx.getWorkitemStatus();
        assertNotNull(ws);
        assertTrue(ws.isEmpty());
    }
}
