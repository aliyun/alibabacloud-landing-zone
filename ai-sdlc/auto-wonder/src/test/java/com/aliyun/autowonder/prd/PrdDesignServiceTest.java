package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDao;
import com.aliyun.autowonder.prd.dto.ConfirmVersionRequest;
import com.aliyun.autowonder.prd.dto.PrdDesignVO;
import com.aliyun.autowonder.prd.dto.PrdDesignVersionVO;
import com.aliyun.autowonder.prd.dto.SaveDesignRequest;
import com.aliyun.autowonder.prd.dto.SelectTargetRequest;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Design lifecycle tests. The PRD DAOs are hand-written in-memory fakes so the optimistic-version
 * CAS semantics the service depends on behave exactly as the SQL does; {@code WorkitemDao} is mocked
 * because only its ownership check matters here.
 */
class PrdDesignServiceTest {

    private static final long WS = 1L;
    private static final long OTHER_WS = 2L;
    private static final long WORKITEM = 10L;
    private static final long USER = 7L;
    private static final long PROFILE = 55L;

    @Test
    void manualTargetNeedsNoProfileOrCommitAndPreservesNotes() {
        Fixture f = new Fixture();
        f.service.getOrCreate(WS, WORKITEM, USER);
        SelectTargetRequest target = new SelectTargetRequest();
        target.setRepoId(42L);
        target.setProjectNotes("React + 用户自定义说明");
        PrdDesignVO saved = f.service.selectTarget(WS, WORKITEM, target, USER);
        assertEquals(".", saved.getTargetAppRoot());
        assertNull(saved.getTargetProfileId());
        assertNull(saved.getTargetCommitSha());
        assertEquals(target.getProjectNotes(), f.service.get(WS, WORKITEM).getTargetProjectNotes());
        PrdDesignVersionVO version = f.save(designJson());
        ConfirmVersionRequest confirm = new ConfirmVersionRequest();
        confirm.setVersionId(version.getId());
        confirm.setExpectedDigest(version.getDesignSha256());
        assertNotNull(f.service.confirm(WS, WORKITEM, confirm, USER));
    }

    @Test
    void manualTargetRejectsEscapingPathsAndForeignRepositories() {
        Fixture f = new Fixture();
        f.service.getOrCreate(WS, WORKITEM, USER);
        SelectTargetRequest target = new SelectTargetRequest();
        target.setRepoId(42L);
        for (String root : List.of("../app", "/app", "apps/../../secret", "C:\\app")) {
            target.setAppRoot(root);
            assertThrows(BizException.class, () -> f.service.selectTarget(WS, WORKITEM, target, USER));
        }
        target.setAppRoot(".");
        target.setRepoId(43L);
        assertThrows(BizException.class, () -> f.service.selectTarget(WS, WORKITEM, target, USER));
    }

    @Test
    void createsDesignAggregateOnFirstAccessAndKeepsItPerWorkitem() {
        Fixture f = new Fixture();

        PrdDesignVO first = f.service.getOrCreate(WS, WORKITEM, USER);
        PrdDesignVO second = f.service.getOrCreate(WS, WORKITEM, USER);

        assertEquals(first.getId(), second.getId(), "one aggregate per workitem");
        assertEquals(DeliveryMode.LEGACY, first.getDeliveryMode(), "existing workitems keep their delivery path");
        assertEquals(0, first.getLatestVersionNo());
        assertNull(first.getConfirmedVersionId());
    }

    @Test
    void renderedArtifactsUseConfiguredDefaultBucketWhenArtifactBucketIsAbsentOrBlank() {
        for (String artifactBucket : new String[]{null, "  "}) {
            Fixture f = new Fixture(artifactBucket);
            PrdDesignVersionVO version = f.save(designJson());
            assertEquals("community-default-bucket", bucketOf(version.getHtmlOssRef()));
            assertTrue(new String(f.service.readHtml(WS, version.getId()), StandardCharsets.UTF_8)
                    .contains("REQ-001"));
        }
    }

    @Test
    void savingADraftRendersHtmlFromTheSameDesignData() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        assertEquals(1, version.getVersionNo());
        assertEquals(PrdVersionStatus.DRAFT, version.getStatus());
        assertNotNull(version.getHtmlOssRef());
        assertEquals(PrdHtmlRenderer.RENDERER_VERSION, version.getRendererVersion());

        // The stored HTML is rendered from the stored design data, not authored separately.
        byte[] html = f.service.readHtml(WS, version.getId());
        PrdDesignData data = PrdDesignCodec.parse(f.versions.raw(version.getId()).getDesignJson());
        assertEquals(PrdHtmlRenderer.render(data), new String(html, StandardCharsets.UTF_8));
        assertTrue(new String(html, StandardCharsets.UTF_8).contains("REQ-001"));
        assertEquals(version.getHtmlSize(), (long) html.length);
        assertEquals(PrdDesignCodec.digest(f.versions.raw(version.getId()).getDesignJson()),
                version.getDesignSha256());
    }

    @Test
    void requirementsExportMatchesTheRenderedHtml() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        PrdDesignData data = PrdDesignCodec.parse(f.versions.raw(version.getId()).getDesignJson());
        String requirements = PrdDesignCodec.exportRequirements(data);
        String html = new String(f.service.readHtml(WS, version.getId()), StandardCharsets.UTF_8);

        assertTrue(requirements.contains("REQ-001"));
        assertTrue(html.contains("REQ-001"));
        assertTrue(requirements.contains("超过 10MB 时拒绝并说明原因"));
        assertTrue(html.contains("超过 10MB 时拒绝并说明原因"));
        assertEquals(2, version.getRequirementCount());
    }

    @Test
    void versionNumbersNeverCollideAndAlwaysIncrease() {
        Fixture f = new Fixture();

        PrdDesignVersionVO first = f.save(designJson());
        PrdDesignVersionVO second = f.save(designJson());
        PrdDesignVersionVO third = f.save(designJson());

        assertEquals(List.of(1, 2, 3), List.of(first.getVersionNo(), second.getVersionNo(), third.getVersionNo()));
        assertEquals(3, f.service.listVersions(WS, WORKITEM).size());
        // History stays metadata-only: the list must not drag every design payload.
        assertNull(f.service.listVersions(WS, WORKITEM).get(0).getDesignJson());
        assertNotNull(f.service.getVersion(WS, first.getId()).getDesignJson());
    }

    @Test
    void rewritingADraftKeepsItsVersionNumber() {
        Fixture f = new Fixture();
        PrdDesignVersionVO draft = f.save(designJson());

        SaveDesignRequest edit = new SaveDesignRequest();
        edit.setVersionId(draft.getId());
        edit.setExpectedVersion(draft.getVersion());
        edit.setDesignJson(designJson("另一个标题"));
        edit.setChangeSummary("调整标题");
        PrdDesignVersionVO rewritten = f.service.saveDraft(WS, WORKITEM, edit, USER);

        assertEquals(draft.getId(), rewritten.getId(), "a draft is rewritten in place");
        assertEquals(1, rewritten.getVersionNo(), "no new version number for an in-place draft edit");
        assertEquals("调整标题", rewritten.getChangeSummary());
        assertNotEquals(draft.getDesignSha256(), rewritten.getDesignSha256());
        assertEquals(1, f.service.listVersions(WS, WORKITEM).size());
    }

    @Test
    void confirmedVersionIsImmutableAndEditingItCreatesANewDraft() {
        Fixture f = new Fixture();
        PrdDesignVersionVO confirmed = f.confirmSavedVersion();

        SaveDesignRequest edit = new SaveDesignRequest();
        edit.setVersionId(confirmed.getId());
        edit.setDesignJson(designJson("改动已确认版本"));
        BizException immutable = assertThrows(BizException.class,
                () -> f.service.saveDraft(WS, WORKITEM, edit, USER));
        assertEquals(ErrorCode.PRD_VERSION_IMMUTABLE.getCode(), immutable.getCode());

        // The supported path: derive a new draft, which keeps requirement keys and history link.
        PrdDesignVersionVO draft = f.service.createDraftFrom(WS, WORKITEM, confirmed.getId(), "评审后调整", USER);
        assertEquals(2, draft.getVersionNo());
        assertEquals(PrdVersionStatus.DRAFT, draft.getStatus());
        assertEquals(confirmed.getId(), draft.getSourceVersionId());
        // The confirmed version itself is untouched, so delivery can keep using it.
        assertEquals(PrdVersionStatus.CONFIRMED, f.service.getVersion(WS, confirmed.getId()).getStatus());
        assertEquals(confirmed.getDesignSha256(), f.service.getVersion(WS, confirmed.getId()).getDesignSha256());
        assertEquals(confirmed.getId(), f.designs.only().getConfirmedVersionId());
    }

    @Test
    void confirmationIsBlockedByAnUnresolvedBlockingQuestion() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(withBlockingQuestion());

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        BizException blocked = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));

        assertEquals(ErrorCode.PRD_CONFIRMATION_BLOCKED.getCode(), blocked.getCode());
        assertNull(f.designs.only().getConfirmedVersionId());
        assertEquals(PrdVersionStatus.DRAFT, f.service.getVersion(WS, version.getId()).getStatus());
        assertEquals(1, version.getBlockingQuestionCount());
    }

    @Test
    void confirmationIsBlockedByARequirementWithoutCriteria() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(requirementWithoutCriteria());

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        request.setIgnoreOpenQuestions(true);
        BizException blocked = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));

        assertEquals(ErrorCode.PRD_CONFIRMATION_BLOCKED.getCode(), blocked.getCode());
        assertTrue(blocked.getMessage().contains("REQ-001"));
    }

    @Test
    void selectingTargetAfterSavingRequiresANewReviewedVersion() {
        Fixture f = new Fixture();
        PrdDesignVersionVO original = f.save(designJson());
        f.selectTarget();
        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(original.getId());
        request.setIgnoreOpenQuestions(true);
        BizException error = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));
        assertEquals(ErrorCode.PRD_TARGET_PROJECT_STALE.getCode(), error.getCode());
        PrdDesignVersionVO refreshed = f.service.createDraftFrom(WS, WORKITEM, original.getId(), null, USER);
        assertEquals(original.getDesignJson(), f.service.getVersion(WS, original.getId()).getDesignJson());
        request.setVersionId(refreshed.getId());
        request.setExpectedDigest(refreshed.getDesignSha256());
        assertEquals(PrdVersionStatus.CONFIRMED, f.service.confirm(WS, WORKITEM, request, USER).getStatus());
    }

    @Test
    void explicitOverrideConfirmsWithoutRewritingQuestionsOrDigest() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(withBlockingQuestion());
        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        request.setExpectedDigest(version.getDesignSha256());
        request.setIgnoreOpenQuestions(true);
        request.setDeliveryMode("PRD_DRIVEN");
        PrdDesignVersionVO confirmed = f.service.confirm(WS, WORKITEM, request, USER);
        assertEquals(PrdVersionStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(version.getDesignJson(), f.service.getVersion(WS, version.getId()).getDesignJson());
        assertEquals(version.getDesignSha256(), confirmed.getConfirmedSha256());
        assertEquals(1, confirmed.getBlockingQuestionCount());
    }

    @Test
    void confirmationRequiresATargetProject() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        BizException missing = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));

        assertEquals(ErrorCode.PRD_TARGET_PROJECT_REQUIRED.getCode(), missing.getCode());
    }

    @Test
    void confirmBindsTheDigestTheReviewerActuallySaw() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(designJson());

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        request.setExpectedDigest("0".repeat(64));
        BizException stale = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));
        assertEquals(ErrorCode.PRD_DIGEST_MISMATCH.getCode(), stale.getCode());

        request.setExpectedDigest(version.getDesignSha256());
        PrdDesignVersionVO confirmed = f.service.confirm(WS, WORKITEM, request, USER);
        assertEquals(PrdVersionStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(version.getDesignSha256(), confirmed.getConfirmedSha256());
        assertEquals(USER, confirmed.getConfirmedBy());
        assertNotNull(confirmed.getConfirmedAt());

        PrdDesignVO design = f.service.get(WS, WORKITEM);
        assertEquals(version.getId(), design.getConfirmedVersionId());
        assertEquals(1, design.getConfirmedVersionNo());
    }

    @Test
    void aConfirmedVersionCannotBeConfirmedTwice() {
        Fixture f = new Fixture();
        PrdDesignVersionVO confirmed = f.confirmSavedVersion();

        ConfirmVersionRequest again = new ConfirmVersionRequest();
        again.setVersionId(confirmed.getId());
        BizException immutable = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, again, USER));

        assertEquals(ErrorCode.PRD_VERSION_IMMUTABLE.getCode(), immutable.getCode());
    }

    @Test
    void aSecondConfirmIsRefusedAndTheWinnerIsKept() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(designJson());

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        f.service.confirm(WS, WORKITEM, request, USER);

        // A second reviewer confirming the same version must be refused: the version is immutable,
        // and the SQL guard additionally refuses any confirm whose status is already CONFIRMED.
        BizException refused = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, 8L));
        assertEquals(ErrorCode.PRD_VERSION_IMMUTABLE.getCode(), refused.getCode());

        // The winner's confirmation is intact and remains the design's confirmed version.
        PrdDesignVersionVO winner = f.service.getVersion(WS, version.getId());
        assertEquals(PrdVersionStatus.CONFIRMED, winner.getStatus());
        assertEquals(USER, winner.getConfirmedBy());
        assertEquals(version.getId(), f.service.get(WS, WORKITEM).getConfirmedVersionId());
    }

    @Test
    void concurrentDraftEditsDoNotSilentlyClobberEachOther() {
        Fixture f = new Fixture();
        PrdDesignVersionVO draft = f.save(designJson());

        // First author edits successfully.
        SaveDesignRequest first = new SaveDesignRequest();
        first.setVersionId(draft.getId());
        first.setExpectedVersion(draft.getVersion());
        first.setDesignJson(designJson("第一处修改"));
        f.service.saveDraft(WS, WORKITEM, first, USER);

        // Second author is still working from the row they loaded before that edit committed, so
        // their optimistic version no longer matches and the CAS refuses the write.
        // The DAO now returns the current row; only the stale client token must reject this edit.
        SaveDesignRequest second = new SaveDesignRequest();
        second.setVersionId(draft.getId());
        second.setExpectedVersion(draft.getVersion());
        second.setDesignJson(designJson("第二处修改"));

        BizException stale = assertThrows(BizException.class,
                () -> f.service.saveDraft(WS, WORKITEM, second, 8L));
        assertEquals(ErrorCode.PRD_VERSION_CONFLICT.getCode(), stale.getCode());

        // The first author's content survives; nothing was overwritten by the loser.
        PrdDesignData surviving = PrdDesignCodec.parse(f.versions.raw(draft.getId()).getDesignJson());
        assertEquals("第一处修改", surviving.getTitle());
    }

    @Test
    void prdDrivenModeIsOnlyEnabledWhenAskedFor() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO version = f.save(designJson());

        ConfirmVersionRequest legacy = new ConfirmVersionRequest();
        legacy.setVersionId(version.getId());
        f.service.confirm(WS, WORKITEM, legacy, USER);
        assertEquals(DeliveryMode.LEGACY, f.service.get(WS, WORKITEM).getDeliveryMode(),
                "an existing workitem must keep its original delivery path");

        PrdDesignVersionVO second = f.save(designJson());
        ConfirmVersionRequest driven = new ConfirmVersionRequest();
        driven.setVersionId(second.getId());
        driven.setDeliveryMode(DeliveryMode.PRD_DRIVEN);
        f.service.confirm(WS, WORKITEM, driven, USER);

        assertEquals(DeliveryMode.PRD_DRIVEN, f.service.get(WS, WORKITEM).getDeliveryMode());
        assertEquals(second.getId(), f.service.get(WS, WORKITEM).getConfirmedVersionId());
    }

    @Test
    void duplicateRequirementKeysAreRejectedAtSave() {
        Fixture f = new Fixture();

        BizException invalid = assertThrows(BizException.class, () -> f.save(duplicateKeys()));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), invalid.getCode());
        assertTrue(f.versions.isEmpty(), "no version may be written for rejected content");
    }

    @Test
    void readHtmlDetectsATamperedArtifact() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());
        PrdDesignVersionDO stored = f.versions.raw(version.getId());

        // Overwrite the object behind the recorded digest.
        f.storage.put(bucketOf(stored.getHtmlOssRef()), keyOf(stored.getHtmlOssRef()),
                "<html>tampered</html>".getBytes(StandardCharsets.UTF_8));

        BizException mismatch = assertThrows(BizException.class, () -> f.service.readHtml(WS, version.getId()));
        assertEquals(ErrorCode.PRD_DIGEST_MISMATCH.getCode(), mismatch.getCode());
    }

    @Test
    void previewUrlIsShortLivedAndFailsWhenTheArtifactIsMissing() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        assertTrue(f.service.previewHtml(WS, version.getId()).startsWith("mem://"));

        f.storage.delete(version.getHtmlOssRef());
        BizException unreadable = assertThrows(BizException.class,
                () -> f.service.previewHtml(WS, version.getId()));
        assertEquals(ErrorCode.PRD_HTML_UNREADABLE.getCode(), unreadable.getCode());
    }

    @Test
    void crossSpaceAccessIsRejectedEverywhere() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        assertThrows(BizException.class, () -> f.service.get(OTHER_WS, WORKITEM));
        assertThrows(BizException.class, () -> f.service.getVersion(OTHER_WS, version.getId()));
        assertThrows(BizException.class, () -> f.service.listVersions(OTHER_WS, WORKITEM));
        assertThrows(BizException.class, () -> f.service.readHtml(OTHER_WS, version.getId()));
        assertThrows(BizException.class, () -> f.service.previewHtml(OTHER_WS, version.getId()));

        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(version.getId());
        BizException crossSpace = assertThrows(BizException.class,
                () -> f.service.confirm(OTHER_WS, WORKITEM, request, USER));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), crossSpace.getCode());
    }

    @Test
    void aWorkitemOfAnotherSpaceCannotHostADesign() {
        Fixture f = new Fixture();
        f.foreignWorkitem();

        BizException notFound = assertThrows(BizException.class,
                () -> f.service.getOrCreate(WS, 99L, USER));
        assertEquals(ErrorCode.WORKITEM_NOT_FOUND.getCode(), notFound.getCode());
    }

    @Test
    void targetProfileMustBelongToTheSameWorkspace() {
        Fixture f = new Fixture();
        f.service.getOrCreate(WS, WORKITEM, USER);
        // Overwrite the fixture's own-workspace profile with one owned by another space.
        f.profiles.put(PROFILE, f.profile(OTHER_WS));

        SelectTargetRequest request = new SelectTargetRequest();
        request.setProfileId(PROFILE);
        BizException notFound = assertThrows(BizException.class,
                () -> f.service.selectTarget(WS, WORKITEM, request, USER));

        assertEquals(ErrorCode.FRONTEND_PROJECT_PROFILE_NOT_FOUND.getCode(), notFound.getCode());
        assertNull(f.designs.only().getTargetProfileId());
    }

    @Test
    void selectingATargetRecordsRepoCommitAndAppRoot() {
        Fixture f = new Fixture();
        f.selectTarget();

        PrdDesignVO design = f.service.get(WS, WORKITEM);
        assertEquals(PROFILE, design.getTargetProfileId());
        assertEquals(42L, design.getTargetRepoId());
        assertEquals("frontend", design.getTargetAppRoot());
        assertEquals("b".repeat(40), design.getTargetCommitSha());
    }

    @Test
    void reviewSubmissionMovesOnlyADraft() {
        Fixture f = new Fixture();
        PrdDesignVersionVO draft = f.save(designJson());

        PrdDesignVersionVO pending = f.service.submitForReview(WS, draft.getId(), USER);
        assertEquals(PrdVersionStatus.PENDING_REVIEW, pending.getStatus());

        // Resubmitting is a no-op race the caller sees as a conflict, not a silent success.
        BizException conflict = assertThrows(BizException.class,
                () -> f.service.submitForReview(WS, draft.getId(), USER));
        assertEquals(ErrorCode.PRD_VERSION_CONFLICT.getCode(), conflict.getCode());
    }

    @Test
    void summaryCountsReflectTheDesignContent() {
        Fixture f = new Fixture();
        PrdDesignVersionVO version = f.save(designJson());

        assertEquals(2, version.getRequirementCount());
        assertEquals(1, version.getOpenQuestionCount());
        assertEquals(0, version.getBlockingQuestionCount());
        assertTrue(version.getSummary().contains("需求 1 项"), "excluded requirements are out of scope: "
                + version.getSummary());
    }

    @Test
    void staleClientCannotOverwriteAnAlreadySavedDraft() {
        Fixture f = new Fixture();
        PrdDesignVersionVO draft = f.save(designJson());
        SaveDesignRequest update = new SaveDesignRequest();
        update.setVersionId(draft.getId());
        update.setExpectedVersion(draft.getVersion());
        update.setDesignJson(designJson("Winner"));
        f.service.saveDraft(WS, WORKITEM, update, USER);
        update.setDesignJson(designJson("Stale loser"));
        assertThrows(BizException.class, () -> f.service.saveDraft(WS, WORKITEM, update, USER));
        assertTrue(f.service.getVersion(WS, draft.getId()).getDesignJson().contains("Winner"));
    }

    @Test
    void changingTargetRequiresANewReviewedVersion() {
        Fixture f = new Fixture();
        f.selectTarget();
        PrdDesignVersionVO saved = f.save(designJson());
        assertEquals(PROFILE, PrdDesignCodec.parse(saved.getDesignJson()).getProjectSnapshot().getProfileId());
        f.designs.only().setTargetProfileId(PROFILE + 1);
        FrontendProjectProfileDO different = f.profile(WS);
        different.setId(PROFILE + 1);
        different.setRepoId(999L);
        f.profiles.put(PROFILE + 1, different);
        ConfirmVersionRequest request = new ConfirmVersionRequest();
        request.setVersionId(saved.getId());
        request.setExpectedDigest(saved.getDesignSha256());
        BizException conflict = assertThrows(BizException.class,
                () -> f.service.confirm(WS, WORKITEM, request, USER));
        assertEquals(ErrorCode.PRD_TARGET_PROJECT_STALE.getCode(), conflict.getCode());
        assertEquals(PROFILE, PrdDesignCodec.parse(f.service.getVersion(WS, saved.getId()).getDesignJson())
                .getProjectSnapshot().getProfileId());
    }

    // ------------------------------------------------------------------ fixture

    private static String bucketOf(String ossRef) {
        return ossRef.substring(0, ossRef.indexOf('/'));
    }

    private static String keyOf(String ossRef) {
        return ossRef.substring(ossRef.indexOf('/') + 1);
    }

    private static String designJson() {
        return designJson("工单批量导入");
    }

    private static String designJson(String title) {
        PrdDesignData data = PrdHtmlRendererTest.sample();
        data.setTitle(title);
        data.getRequirements().get(0).setAcceptanceCriteria(
                new ArrayList<>(List.of("超过 10MB 时拒绝并说明原因")));
        data.getOpenQuestions().get(0).setBlocking(false);
        return PrdDesignCodec.serialize(data);
    }

    private static String withBlockingQuestion() {
        PrdDesignData data = PrdHtmlRendererTest.sample();
        data.getOpenQuestions().get(0).setBlocking(true);
        data.getOpenQuestions().get(0).setResolved(false);
        return PrdDesignCodec.serialize(data);
    }

    private static String requirementWithoutCriteria() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey("REQ-001");
        requirement.setTitle("没有验收标准");
        data.setRequirements(new ArrayList<>(List.of(requirement)));
        return PrdDesignCodec.serialize(data);
    }

    private static String duplicateKeys() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        data.setRequirements(new ArrayList<>(List.of(
                requirement("REQ-001", "第一个"), requirement("REQ-001", "重复"))));
        return PrdDesignCodec.serialize(data);
    }

    private static PrdDesignData.Requirement requirement(String key, String title) {
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey(key);
        requirement.setTitle(title);
        requirement.setAcceptanceCriteria(new ArrayList<>(List.of("标准")));
        return requirement;
    }

    private final class Fixture {
        final InMemoryDesignDao designs = new InMemoryDesignDao();
        final InMemoryVersionDao versions = new InMemoryVersionDao();
        final InMemoryProfileDao profiles = new InMemoryProfileDao();
        final InMemoryObjectStorage storage = new InMemoryObjectStorage();
        final WorkitemDao workitemDao = mock(WorkitemDao.class);
        final PrdDesignService service;

        Fixture() {
            this("prd-test-bucket");
        }

        Fixture(String artifactBucket) {
            OssProperties oss = new OssProperties();
            oss.setBucket("community-default-bucket");
            oss.setArtifactBucket(artifactBucket);
            service = new PrdDesignService(designs, versions, workitemDao, profiles,
                    storage, null, oss);
            WorkitemDO workitem = new WorkitemDO();
            workitem.setId(WORKITEM);
            workitem.setTenantId(WS);
            when(workitemDao.findById(WORKITEM)).thenReturn(workitem);
            profiles.put(PROFILE, profile(WS));
            var repos = mock(com.aliyun.autowonder.repo.RepoDao.class);
            var repo = new com.aliyun.autowonder.repo.RepoDO();
            repo.setId(42L);
            repo.setTenantId(WS);
            repo.setDefaultBranch("main");
            when(repos.findById(42L)).thenReturn(repo);
            service.setRepoDao(repos);
        }

        void foreignWorkitem() {
            WorkitemDO foreign = new WorkitemDO();
            foreign.setId(99L);
            foreign.setTenantId(OTHER_WS);
            when(workitemDao.findById(99L)).thenReturn(foreign);
        }

        FrontendProjectProfileDO profile(long workspaceId) {
            FrontendProjectProfileDO profile = new FrontendProjectProfileDO();
            profile.setId(PROFILE);
            profile.setWorkspaceId(workspaceId);
            profile.setRepoId(42L);
            profile.setAppRoot("frontend");
            profile.setCommitSha("b".repeat(40));
            profile.setDetectorVersion("frontend-project-detector-v1");
            return profile;
        }

        PrdDesignVersionVO save(String designJson) {
            service.getOrCreate(WS, WORKITEM, USER);
            SaveDesignRequest request = new SaveDesignRequest();
            request.setDesignJson(designJson);
            return service.saveDraft(WS, WORKITEM, request, USER);
        }

        void selectTarget() {
            service.getOrCreate(WS, WORKITEM, USER);
            SelectTargetRequest request = new SelectTargetRequest();
            request.setProfileId(PROFILE);
            service.selectTarget(WS, WORKITEM, request, USER);
        }

        PrdDesignVersionVO confirmSavedVersion() {
            selectTarget();
            PrdDesignVersionVO version = save(designJson());
            ConfirmVersionRequest request = new ConfirmVersionRequest();
            request.setVersionId(version.getId());
            request.setExpectedDigest(version.getDesignSha256());
            return service.confirm(WS, WORKITEM, request, USER);
        }
    }

    private static final class InMemoryDesignDao implements PrdDesignDao {
        final Map<Long, PrdDesignDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(1000);

        /** The single aggregate this fixture creates; test convenience accessor. */
        PrdDesignDO only() {
            return rows.values().iterator().next();
        }

        @Override public void insert(PrdDesignDO design) {
            design.setId(ids.incrementAndGet());
            design.setVersion(0);
            design.setGmtCreate(new Date());
            design.setGmtModified(new Date());
            rows.put(design.getId(), design);
        }

        @Override public PrdDesignDO findById(Long id, Long workspaceId) {
            PrdDesignDO design = rows.get(id);
            return design != null && design.getWorkspaceId().equals(workspaceId) ? copy(design) : null;
        }

        @Override public PrdDesignDO findByWorkitem(Long workspaceId, Long workitemId) {
            return rows.values().stream()
                    .filter(d -> d.getWorkspaceId().equals(workspaceId) && d.getWorkitemId().equals(workitemId))
                    .findFirst().map(InMemoryDesignDao::copy).orElse(null);
        }

        /**
         * Return a copy, never the live row: real MyBatis hands the caller a detached DO and an
         * UPDATE statement never mutates it, so the service's post-increment arithmetic must be
         * exercised against the pre-increment snapshot it actually holds.
         */
        private static PrdDesignDO copy(PrdDesignDO source) {
            PrdDesignDO copy = new PrdDesignDO();
            copy.setId(source.getId());
            copy.setWorkspaceId(source.getWorkspaceId());
            copy.setWorkitemId(source.getWorkitemId());
            copy.setTargetRepoId(source.getTargetRepoId());
            copy.setTargetProfileId(source.getTargetProfileId());
            copy.setTargetAppRoot(source.getTargetAppRoot());
            copy.setTargetCommitSha(source.getTargetCommitSha());
            copy.setTargetConfigJson(source.getTargetConfigJson());
            copy.setConfirmedVersionId(source.getConfirmedVersionId());
            copy.setDeliveryMode(source.getDeliveryMode());
            copy.setLatestVersionNo(source.getLatestVersionNo());
            copy.setContentLanguage(source.getContentLanguage());
            copy.setVersion(source.getVersion());
            copy.setCreatorId(source.getCreatorId());
            copy.setModifierId(source.getModifierId());
            copy.setGmtCreate(source.getGmtCreate());
            copy.setGmtModified(source.getGmtModified());
            return copy;
        }

        @Override public int updateTarget(Long id, Long workspaceId, Long targetRepoId, Long targetProfileId,
                                          String targetAppRoot, String targetCommitSha,
                                          Integer expectedVersion, Long modifierId) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) {
                return 0;
            }
            design.setTargetRepoId(targetRepoId);
            design.setTargetProfileId(targetProfileId);
            design.setTargetAppRoot(targetAppRoot);
            design.setTargetCommitSha(targetCommitSha);
            design.setModifierId(modifierId);
            return 1;
        }

        @Override public int updateManualTarget(Long id, Long workspaceId, Long repoId, String appRoot,
                                                String configJson, Integer expectedVersion, Long modifierId) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) return 0;
            design.setTargetRepoId(repoId);
            design.setTargetAppRoot(appRoot);
            design.setTargetProfileId(null);
            design.setTargetCommitSha(null);
            design.setTargetConfigJson(configJson);
            design.setModifierId(modifierId);
            return 1;
        }

        @Override public int updateDeliveryMode(Long id, Long workspaceId, String deliveryMode,
                                                Integer expectedVersion, Long modifierId) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) {
                return 0;
            }
            design.setDeliveryMode(deliveryMode);
            design.setModifierId(modifierId);
            return 1;
        }

        @Override public int updateContentLanguage(Long id, Long workspaceId, String contentLanguage,
                                                   Integer expectedVersion, Long modifierId) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) {
                return 0;
            }
            design.setContentLanguage(contentLanguage);
            return 1;
        }

        @Override public int incrementLatestVersionNo(Long id, Long workspaceId, Integer expectedVersion) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) {
                return 0;
            }
            design.setLatestVersionNo(design.getLatestVersionNo() + 1);
            return 1;
        }

        @Override public int confirmVersion(Long id, Long workspaceId, Long confirmedVersionId,
                                            Integer expectedVersion, Long modifierId) {
            PrdDesignDO design = scoped(rows.get(id), workspaceId);
            if (!cas(design, expectedVersion)) {
                return 0;
            }
            design.setConfirmedVersionId(confirmedVersionId);
            design.setModifierId(modifierId);
            return 1;
        }

        private static PrdDesignDO scoped(PrdDesignDO design, Long workspaceId) {
            return design != null && design.getWorkspaceId().equals(workspaceId) ? design : null;
        }

        private static boolean cas(PrdDesignDO design, Integer expectedVersion) {
            if (design == null || !design.getVersion().equals(expectedVersion)) {
                return false;
            }
            design.setVersion(design.getVersion() + 1);
            design.setGmtModified(new Date());
            return true;
        }
    }

    private static final class InMemoryVersionDao implements PrdDesignVersionDao {
        final Map<Long, PrdDesignVersionDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(5000);

        /**
         * Simulates a caller working from a stale read: the row version handed to the CAS is
         * decremented by this offset, so the optimistic update loses the race.
         */
        int staleVersionOffset = 0;

        /** The live stored row (mutable), used to simulate a stale CAS in concurrency tests. */
        PrdDesignVersionDO raw(Long id) {
            return rows.get(id);
        }

        boolean isEmpty() {
            return rows.isEmpty();
        }

        @Override public void insert(PrdDesignVersionDO version) {
            boolean duplicate = rows.values().stream().anyMatch(v ->
                    v.getWorkspaceId().equals(version.getWorkspaceId())
                            && v.getDesignId().equals(version.getDesignId())
                            && v.getVersionNo().equals(version.getVersionNo())
                            && Integer.valueOf(0).equals(v.getIsDeleted()));
            if (duplicate) {
                throw new org.springframework.dao.DuplicateKeyException("uk_prd_version");
            }
            version.setId(ids.incrementAndGet());
            version.setVersion(0);
            version.setIsDeleted(0);
            version.setGmtCreate(new Date());
            version.setGmtModified(new Date());
            rows.put(version.getId(), version);
        }

        @Override public PrdDesignVersionDO findById(Long id, Long workspaceId) {
            PrdDesignVersionDO version = rows.get(id);
            if (version == null || !version.getWorkspaceId().equals(workspaceId)
                    || Integer.valueOf(1).equals(version.getIsDeleted())) {
                return null;
            }
            PrdDesignVersionDO copy = copy(version);
            // A stale read hands the service an outdated optimistic version, so its CAS loses.
            if (staleVersionOffset > 0) {
                copy.setVersion(Math.max(0, copy.getVersion() - staleVersionOffset));
            }
            return copy;
        }

        @Override public PrdDesignVersionDO findByVersionNo(Long workspaceId, Long designId, Integer versionNo) {
            return rows.values().stream()
                    .filter(v -> v.getWorkspaceId().equals(workspaceId) && v.getDesignId().equals(designId)
                            && v.getVersionNo().equals(versionNo))
                    .findFirst().map(InMemoryVersionDao::copy).orElse(null);
        }

        @Override public List<PrdDesignVersionDO> listByDesign(Long workspaceId, Long designId) {
            return rows.values().stream()
                    .filter(v -> v.getWorkspaceId().equals(workspaceId) && v.getDesignId().equals(designId)
                            && !Integer.valueOf(1).equals(v.getIsDeleted()))
                    .sorted((a, b) -> b.getVersionNo().compareTo(a.getVersionNo()))
                    .map(v -> {
                        // Metadata-only projection, mirroring the listCols SQL fragment.
                        PrdDesignVersionDO light = copy(v);
                        light.setDesignJson(null);
                        return light;
                    })
                    .toList();
        }

        @Override public PrdDesignVersionDO findLatestByDesign(Long workspaceId, Long designId) {
            return rows.values().stream()
                    .filter(v -> v.getWorkspaceId().equals(workspaceId) && v.getDesignId().equals(designId))
                    .max((a, b) -> a.getVersionNo().compareTo(b.getVersionNo()))
                    .map(InMemoryVersionDao::copy).orElse(null);
        }

        @Override public PrdDesignVersionDO findByAiSession(Long workspaceId, Long aiSessionId) {
            return rows.values().stream()
                    .filter(v -> v.getWorkspaceId().equals(workspaceId)
                            && aiSessionId.equals(v.getAiSessionId()))
                    .findFirst().map(InMemoryVersionDao::copy).orElse(null);
        }

        @Override public int updateDraft(PrdDesignVersionDO patch, Integer expectedVersion, Long modifierId) {
            PrdDesignVersionDO stored = editable(patch.getId(), patch.getWorkspaceId(), expectedVersion);
            if (stored == null || !PrdVersionStatus.DRAFT.equals(stored.getStatus())) {
                return 0;
            }
            stored.setDesignJson(patch.getDesignJson());
            stored.setDesignSha256(patch.getDesignSha256());
            stored.setHtmlOssRef(patch.getHtmlOssRef());
            stored.setHtmlSha256(patch.getHtmlSha256());
            stored.setHtmlSize(patch.getHtmlSize());
            stored.setRendererVersion(patch.getRendererVersion());
            stored.setSummary(patch.getSummary());
            stored.setChangeSummary(patch.getChangeSummary());
            stored.setRequirementCount(patch.getRequirementCount());
            stored.setOpenQuestionCount(patch.getOpenQuestionCount());
            stored.setBlockingQuestionCount(patch.getBlockingQuestionCount());
            stored.setModifierId(modifierId);
            stored.setVersion(stored.getVersion() + 1);
            return 1;
        }

        @Override public int attachHtml(Long id, Long workspaceId, String htmlOssRef,
                                        String htmlSha256, Long htmlSize) {
            PrdDesignVersionDO stored = rows.get(id);
            if (stored == null || !stored.getWorkspaceId().equals(workspaceId)) {
                return 0;
            }
            stored.setHtmlOssRef(htmlOssRef);
            stored.setHtmlSha256(htmlSha256);
            stored.setHtmlSize(htmlSize);
            return 1;
        }

        @Override public int submitForReview(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            PrdDesignVersionDO stored = editable(id, workspaceId, expectedVersion);
            if (stored == null || !PrdVersionStatus.DRAFT.equals(stored.getStatus())) {
                return 0;
            }
            stored.setStatus(PrdVersionStatus.PENDING_REVIEW);
            stored.setModifierId(modifierId);
            stored.setVersion(stored.getVersion() + 1);
            return 1;
        }

        @Override public int confirm(Long id, Long workspaceId, String confirmedSha256,
                                     Long confirmedBy, Integer expectedVersion) {
            PrdDesignVersionDO stored = editable(id, workspaceId, expectedVersion);
            if (stored == null || PrdVersionStatus.CONFIRMED.equals(stored.getStatus())) {
                return 0;
            }
            stored.setStatus(PrdVersionStatus.CONFIRMED);
            stored.setConfirmedSha256(confirmedSha256);
            stored.setConfirmedBy(confirmedBy);
            stored.setConfirmedAt(new Date());
            stored.setVersion(stored.getVersion() + 1);
            return 1;
        }

        @Override public int softDelete(Long id, Long workspaceId, Integer expectedVersion, Long modifierId) {
            PrdDesignVersionDO stored = editable(id, workspaceId, expectedVersion);
            if (stored == null || PrdVersionStatus.CONFIRMED.equals(stored.getStatus())) {
                return 0;
            }
            stored.setIsDeleted(1);
            stored.setDeletedAt(new Date());
            stored.setVersion(stored.getVersion() + 1);
            return 1;
        }

        private PrdDesignVersionDO editable(Long id, Long workspaceId, Integer expectedVersion) {
            PrdDesignVersionDO stored = rows.get(id);
            if (stored == null || !stored.getWorkspaceId().equals(workspaceId)
                    || !stored.getVersion().equals(expectedVersion)
                    || Integer.valueOf(1).equals(stored.getIsDeleted())) {
                return null;
            }
            return stored;
        }

        private static PrdDesignVersionDO copy(PrdDesignVersionDO source) {
            PrdDesignVersionDO copy = new PrdDesignVersionDO();
            copy.setId(source.getId());
            copy.setWorkspaceId(source.getWorkspaceId());
            copy.setDesignId(source.getDesignId());
            copy.setVersionNo(source.getVersionNo());
            copy.setStatus(source.getStatus());
            copy.setSourceVersionId(source.getSourceVersionId());
            copy.setDesignJson(source.getDesignJson());
            copy.setDesignSha256(source.getDesignSha256());
            copy.setHtmlOssRef(source.getHtmlOssRef());
            copy.setHtmlSha256(source.getHtmlSha256());
            copy.setHtmlSize(source.getHtmlSize());
            copy.setRendererVersion(source.getRendererVersion());
            copy.setSummary(source.getSummary());
            copy.setChangeSummary(source.getChangeSummary());
            copy.setRequirementCount(source.getRequirementCount());
            copy.setOpenQuestionCount(source.getOpenQuestionCount());
            copy.setBlockingQuestionCount(source.getBlockingQuestionCount());
            copy.setAiSessionId(source.getAiSessionId());
            copy.setConfirmedBy(source.getConfirmedBy());
            copy.setConfirmedAt(source.getConfirmedAt());
            copy.setConfirmedSha256(source.getConfirmedSha256());
            copy.setIsDeleted(source.getIsDeleted());
            copy.setVersion(source.getVersion());
            copy.setGmtCreate(source.getGmtCreate());
            copy.setGmtModified(source.getGmtModified());
            return copy;
        }
    }

    private static final class InMemoryProfileDao implements FrontendProjectProfileDao {
        final Map<Long, FrontendProjectProfileDO> rows = new LinkedHashMap<>();

        void put(Long id, FrontendProjectProfileDO profile) {
            rows.put(id, profile);
        }

        @Override public int insertIgnore(FrontendProjectProfileDO profile) {
            rows.put(profile.getId(), profile);
            return 1;
        }

        @Override public FrontendProjectProfileDO findById(Long id, Long workspaceId) {
            FrontendProjectProfileDO profile = id == null ? null : rows.get(id);
            return profile != null && profile.getWorkspaceId().equals(workspaceId) ? profile : null;
        }

        @Override public FrontendProjectProfileDO findByCacheKey(Long workspaceId, Long repoId, String commitSha,
                                                                 String appRootHash, String detectorVersion) {
            return null;
        }

        @Override public List<FrontendProjectProfileDO> listByRepo(Long workspaceId, Long repoId, int limit) {
            return List.of();
        }

        @Override public List<FrontendProjectProfileDO> listByProbe(Long workspaceId, Long probeId) {
            return List.of();
        }

        @Override public List<FrontendProjectProfileDO> listByCommit(Long workspaceId, Long repoId,
                                                                     String commitSha, String detectorVersion) {
            return List.of();
        }

        @Override public int deleteByRepo(Long workspaceId, Long repoId) {
            return 0;
        }
    }
}
