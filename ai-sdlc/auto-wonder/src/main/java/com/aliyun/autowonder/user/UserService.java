package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.ChangePasswordRequest;
import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.user.dto.LogoutRequest;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserVO;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserService {
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9][0-9 -]*$");

    private final UserDao userDao;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final JwtProperties jwtProperties;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final SystemAdminService systemAdminService;

    public UserService(UserDao userDao, JwtService jwtService,
                       SessionService sessionService, JwtProperties jwtProperties,
                       WorkspaceMemberDao workspaceMemberDao,
                       SystemAdminService systemAdminService) {
        this.userDao = userDao;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.jwtProperties = jwtProperties;
        this.workspaceMemberDao = workspaceMemberDao;
        this.systemAdminService = systemAdminService;
    }

    public UserVO register(RegisterRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        if (userDao.findByUsername(req.getUsername()) != null) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        UserDO user = new UserDO();
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setNickname(req.getNickname());
        user.setPasswordHash(PasswordEncoderUtil.encode(req.getPassword()));
        user.setStatus(0);
        userDao.insert(user);
        // New-install initialization: the first active user registered on a platform that has
        // no admin yet becomes the platform admin. A no-op once any admin exists, so later
        // registrations never change the roster and a demoted first user is never re-granted.
        systemAdminService.ensureSystemAdmin();

        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        return vo;
    }

    public LoginResponse login(LoginRequest req) {
        UserDO user = userDao.findByUsername(req.getUsername());
        if (user == null || user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 1
                && "DEACTIVATED".equals(user.getPasswordHash())) {
            throw new BizException(ErrorCode.DEACTIVATION_ACCOUNT_DISABLED);
        }
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.signAccess(new TokenPayload(user.getId(), null, jti));
        String refreshToken = UUID.randomUUID().toString();
        sessionService.storeRefresh(refreshToken, user.getId(), (int) jwtProperties.getRefreshTtlSeconds());
        return new LoginResponse(user.getId(), accessToken, refreshToken, toVO(user));
    }

    public void logout(LogoutRequest req) {
        if (req != null && req.getRefreshToken() != null) {
            sessionService.revokeRefresh(req.getRefreshToken());
        }
    }

    public String refreshAccessToken(String refreshToken, Long workspaceId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "refreshToken 不能为空");
        }
        Long userId = sessionService.getUserIdByRefresh(refreshToken);
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
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
            throw new BizException(ErrorCode.PARAM_INVALID, "旧密码不能为空");
        }
        if (req.getNewPassword() == null || req.getNewPassword().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能为空");
        }
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "旧密码不正确");
        }
        userDao.updatePasswordHash(userId, PasswordEncoderUtil.encode(req.getNewPassword()));
    }

    public UserProfileVO getProfile(Long userId) {
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return toProfileVO(user);
    }

    public UserProfileVO updateProfile(Long userId, UpdateProfileRequest req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求不能为空");
        }
        String nickname = trimToNull(req.getNickname());
        if (nickname == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "昵称不能为空");
        }
        if (nickname.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "昵称最多 64 个字符");
        }
        String email = trimToNull(req.getEmail());
        if (email != null) {
            if (email.length() > 128) {
                throw new BizException(ErrorCode.PARAM_INVALID, "邮箱最多 128 个字符");
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "邮箱格式不正确");
            }
        }
        String phone = trimToNull(req.getPhone());
        if (phone != null) {
            if (phone.length() > 32) {
                throw new BizException(ErrorCode.PARAM_INVALID, "联系方式最多 32 个字符");
            }
            if (!PHONE_PATTERN.matcher(phone).matches()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "联系方式仅支持数字、空格、连字符，可带国际区号前缀 +");
            }
        }
        if (userDao.findById(userId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        try {
            userDao.updateProfile(userId, nickname, email, phone);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已被使用");
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
        return vo;
    }

    private UserVO toVO(UserDO user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setIsAdmin(Integer.valueOf(1).equals(user.getIsAdmin()));
        return vo;
    }
}
