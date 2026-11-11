package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import com.aliyun.autowonder.user.dto.ChangePasswordRequest;
import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.user.dto.LogoutRequest;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.RegisterResponse;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserVO;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import com.aliyun.autowonder.user.event.UserRegistrationReviewRequestedEvent;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserService {
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9][0-9 -]*$");
    /**
     * 逻辑删除墓碑用户名（{@code #deleted-{id}}）的保留命名空间，注册禁用。
     * uk_username 沿用库默认排序规则（大小写/重音不敏感），只比对 {@code #deleted-} 字面量
     * 拦不住 {@code #DELETED-{id}}、{@code #déleted-{id}} 等等价变体，故整个 {@code #} 前缀保留。
     */
    public static final String RESERVED_USERNAME_PREFIX = "#";
    /** 与前端一致的最短密码长度。 */
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserDao userDao;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final JwtProperties jwtProperties;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final SystemAdminService systemAdminService;
    private final SessionIssuanceService sessionIssuanceService;
    private final PlatformFeatureSwitchService featureSwitchService;
    private final WorkspaceCreationPermissionService workspaceCreationPermissionService;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserDao userDao, JwtService jwtService,
                       SessionService sessionService, JwtProperties jwtProperties,
                       WorkspaceMemberDao workspaceMemberDao,
                       SystemAdminService systemAdminService,
                       SessionIssuanceService sessionIssuanceService,
                       PlatformFeatureSwitchService featureSwitchService,
                       WorkspaceCreationPermissionService workspaceCreationPermissionService,
                       ApplicationEventPublisher eventPublisher) {
        this.userDao = userDao;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.jwtProperties = jwtProperties;
        this.workspaceMemberDao = workspaceMemberDao;
        this.systemAdminService = systemAdminService;
        this.sessionIssuanceService = sessionIssuanceService;
        this.featureSwitchService = featureSwitchService;
        this.workspaceCreationPermissionService = workspaceCreationPermissionService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 自助注册：审核开关在事务内只读取一次，据此写入待审核或正常状态。
     * 开关关闭时保留原注册后跳转；已存在的待审账号不因开关变化而被改写。
     */
    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.username_required", null,
                    "用户名不能为空");
        }
        // 逻辑删除会把用户名改写为 #deleted-{id} 以释放唯一键；墓碑名必须对注册不可达，
        // 否则同名占位行会让后续删除撞 uk_username。
        if (req.getUsername().startsWith(RESERVED_USERNAME_PREFIX)) {
            throw new BizException(ErrorCode.USER_ADMIN_USERNAME_RESERVED);
        }
        if (userDao.findByUsername(req.getUsername()) != null) {
            throw BizException.keyed(ErrorCode.CONFLICT, "user.username_exists", null,
                    "用户名已存在");
        }
        boolean reviewRequired = featureSwitchService.registrationReviewEnabled();
        UserDO user = new UserDO();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setNickname(req.getNickname());
        user.setPasswordHash(PasswordEncoderUtil.encode(req.getPassword()));
        user.setStatus(reviewRequired
                ? UserAccountStates.STATUS_PENDING_REVIEW
                : UserAccountStates.STATUS_NORMAL);
        try {
            userDao.insert(user);
        } catch (DuplicateKeyException e) {
            throw BizException.keyed(ErrorCode.CONFLICT, "user.email_in_use", null,
                    "邮箱已被使用");
        }
        // New-install initialization: the first active user registered on a platform that has
        // no admin yet becomes the platform admin. Pending registrations never become admins.
        if (!reviewRequired) {
            systemAdminService.ensureSystemAdmin();
        }

        RegisterResponse response = RegisterResponse.from(toUserVO(user));
        if (reviewRequired) {
            response.setReviewRequired(true);
            response.setAdministratorContacts(workspaceCreationPermissionService.availableAdministratorContacts());
            // 提交后监听器负责按接收者语言发送平台站内通知；单条失败不影响注册结果。
            eventPublisher.publishEvent(new UserRegistrationReviewRequestedEvent(
                    user.getId(), user.getNickname(), user.getUsername()));
        }
        return response;
    }

    public LoginResponse login(LoginRequest req) {
        UserDO user = userDao.findByUsername(req.getUsername());
        // A pure-SSO account carries no password hash at all; it simply cannot pass password login.
        if (user == null || user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(req.getPassword(), user.getPasswordHash())) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.credentials_incorrect", null,
                    "用户名或密码错误");
        }
        if (UserAccountStates.isLegacyDeactivated(user)) {
            throw new BizException(ErrorCode.DEACTIVATION_ACCOUNT_DISABLED);
        }
        // 正确凭据下待审账号不签发会话，只返回审核提示与管理员联系方式；错误凭据保持原错误。
        if (UserAccountStates.isPendingReview(user)) {
            throw new RegistrationReviewPendingException(
                    workspaceCreationPermissionService.availableAdministratorContacts());
        }
        return sessionIssuanceService.issue(user);
    }

    public void logout(LogoutRequest req) {
        if (req != null && req.getRefreshToken() != null) {
            sessionService.revokeRefresh(req.getRefreshToken());
        }
    }

    /** 后台轮询与自动重连只换发访问令牌，不顺延会话到期时间。 */
    public String refreshAccessToken(String refreshToken, Long workspaceId) {
        return refreshAccessToken(refreshToken, workspaceId, false);
    }

    public String refreshAccessToken(String refreshToken, Long workspaceId, boolean userActivity) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.refresh_token_required", null,
                    "refreshToken 不能为空");
        }
        Long userId = sessionService.getUserIdByRefresh(refreshToken);
        if (userId == null) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.refresh_token_expired", null,
                    "刷新令牌无效或已过期");
        }
        // 待审或已删除账号即使仍持有刷新令牌也不能换发访问令牌。
        UserDO sessionUser = userDao.findById(userId);
        if (sessionUser == null) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.refresh_token_expired", null,
                    "刷新令牌无效或已过期");
        }
        if (UserAccountStates.isPendingReview(sessionUser)) {
            throw new RegistrationReviewPendingException(
                    workspaceCreationPermissionService.availableAdministratorContacts());
        }
        if (UserAccountStates.isLegacyDeactivated(sessionUser)) {
            throw new BizException(ErrorCode.DEACTIVATION_ACCOUNT_DISABLED);
        }
        if (userActivity && sessionService.extendRefreshSession(
                refreshToken, (int) jwtProperties.getRefreshTtlSeconds())
                == SessionService.RenewalOutcome.SESSION_MISSING) {
            // 读取会话后、顺延前被登出或撤销：不能为已失效的会话签出新的访问令牌。
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.refresh_token_expired", null,
                    "刷新令牌无效或已过期");
        }
        String jti = UUID.randomUUID().toString();
        return jwtService.signAccess(
                new TokenPayload(userId, resolveWorkspaceClaim(userId, workspaceId), jti));
    }

    // AuthFilter reads the workspace claim back into the request context, so signing it as null
    // detaches the session from its workspace and every workspace-scoped call fails 11001.
    // The claim is re-validated instead of trusted: the caller declares, the server decides.
    private Long resolveWorkspaceClaim(Long userId, Long workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null
                || !Integer.valueOf(0).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getIsDeleted())) {
            return null;
        }
        return workspaceId;
    }

    public void changePassword(Long userId, ChangePasswordRequest req) {
        if (req.getOldPassword() == null || req.getOldPassword().isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.old_password_required", null,
                    "旧密码不能为空");
        }
        if (req.getNewPassword() == null || req.getNewPassword().isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.new_password_required", null,
                    "新密码不能为空");
        }
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null,
                    "用户不存在");
        }
        if (user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.old_password_incorrect", null,
                    "旧密码不正确");
        }
        userDao.updatePasswordHash(userId, PasswordEncoderUtil.encode(req.getNewPassword()));
    }

    public UserProfileVO getProfile(Long userId) {
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null,
                    "用户不存在");
        }
        return toProfileVO(user);
    }

    /**
     * 管理员重置密码：无原密码校验，但密码强度门槛与注册一致（至少 {@value #MIN_PASSWORD_LENGTH} 位）。
     * 账号状态守卫（已删除/已注销只读）由调用方 UserAdminService 统一执行。
     */
    public void resetPassword(Long targetUserId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.new_password_required", null,
                    "新密码不能为空");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.new_password_too_short",
                    new Object[]{MIN_PASSWORD_LENGTH},
                    "新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (userDao.updatePasswordHash(targetUserId, PasswordEncoderUtil.encode(newPassword)) != 1) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null,
                    "用户不存在");
        }
    }

    public UserProfileVO updateProfile(Long userId, UpdateProfileRequest req) {
        if (req == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.request_required", null,
                    "请求不能为空");
        }
        String nickname = trimToNull(req.getNickname());
        if (nickname == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.nickname_required", null,
                    "昵称不能为空");
        }
        if (nickname.length() > 64) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.nickname_too_long", new Object[]{64},
                    "昵称最多 64 个字符");
        }
        String email = trimToNull(req.getEmail());
        if (email != null) {
            if (email.length() > 128) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.email_too_long", new Object[]{128},
                    "邮箱最多 128 个字符");
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.email_invalid", null,
                    "邮箱格式不正确");
            }
        }
        String phone = trimToNull(req.getPhone());
        if (phone != null) {
            if (phone.length() > 32) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.phone_too_long", new Object[]{32},
                    "联系方式最多 32 个字符");
            }
            if (!PHONE_PATTERN.matcher(phone).matches()) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.phone_invalid", null,
                    "联系方式仅支持数字、空格、连字符，可带国际区号前缀 +");
            }
        }
        if (userDao.findById(userId) == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null,
                    "用户不存在");
        }
        try {
            userDao.updateProfile(userId, nickname, email, phone);
        } catch (DuplicateKeyException e) {
            throw BizException.keyed(ErrorCode.CONFLICT, "user.email_in_use", null,
                    "邮箱已被使用");
        }
        return getProfile(userId);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserProfileVO toProfileVO(UserDO user) {
        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setCanCreateWorkspace(workspaceCreationPermissionService.canCreate(user));
        return vo;
    }

    private UserVO toUserVO(UserDO user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setIsAdmin(Integer.valueOf(1).equals(user.getIsAdmin()));
        return vo;
    }
}
