package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.Passwords;
import com.ai.konwledgerepo.dto.LoginRequest;
import com.ai.konwledgerepo.dto.LoginResponse;
import com.ai.konwledgerepo.dto.WorkspaceInfo;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.Workspace;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.repository.WorkspaceRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认证业务：登录校验、当前用户上下文、登出。
 * 负责用户/成员加载、token 签发与响应组装；AuthController 仅做参数转发。
 */
@Service
public class AuthService {

    private final SysUserRepository userRepository;
    private final TokenService tokenService;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    public AuthService(SysUserRepository userRepository,
                       TokenService tokenService,
                       WorkspaceMemberRepository memberRepository,
                       WorkspaceRepository workspaceRepository,
                       PasswordEncoder passwordEncoder,
                       LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
    }

    /** 登录：校验用户名/密码/启停状态，签发 token，返回默认工作空间上下文 */
    public LoginResponse login(LoginRequest request) {
        String username = request.username();

        // 登录防爆破：被锁定则统一返回用户名密码错误（防用户名枚举）
        if (loginAttemptService.isLocked(username)) {
            throw new BizException("用户名或密码错误");
        }

        SysUser user = userRepository.findByUsername(username)
                .orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            loginAttemptService.recordFailure(username);
            throw new BizException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginAttemptService.recordFailure(username);
            throw new BizException("用户名或密码错误");
        }

        // 登录成功，清除失败计数与锁定
        loginAttemptService.clear(username);

        List<WorkspaceMember> members = memberRepository.findByUserIdOrderByIdAsc(user.getId());
        if (members.isEmpty()) {
            throw new BizException(Defaults.NO_WORKSPACE_MESSAGE);
        }
        String token = tokenService.create(user.getId());
        return buildResponse(token, user, members, members.get(0).getWorkspaceId());
    }

    /** 当前用户上下文（token 已由拦截器校验）；无成员关系时 workspaceId 为 null */
    public LoginResponse me(Long userId, Long workspaceId) {
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCodes.UNAUTHORIZED, "用户不存在"));
        List<WorkspaceMember> members = memberRepository.findByUserIdOrderByIdAsc(userId);
        return buildResponse(null, user, members, workspaceId);
    }

    /** 登出：移除 token（若请求带 Bearer token） */
    public void logout(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            tokenService.remove(authorization.substring(7));
        }
    }

    /**
     * 自助修改密码（对齐 Dify /account/password 语义）：旧密码校验 + 新密码策略 + 重新 BCrypt 落库。
     * 改密成功即清除「须改密」标记（管理员重置后首次登录改完即恢复正常）。
     * 已有 token 保持有效（改密不等于登出，与 Dify 一致）。
     */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword, String confirmPassword) {
        if (!java.util.Objects.equals(newPassword, confirmPassword)) {
            throw new BizException("两次输入的新密码不一致");
        }
        Passwords.validate(newPassword);
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCodes.UNAUTHORIZED, "用户不存在"));
        if (!passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPassword())) {
            throw new BizException("当前密码不正确");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    private LoginResponse buildResponse(String token, SysUser user, List<WorkspaceMember> members, Long currentWorkspaceId) {
        Map<Long, Workspace> workspaceMap = workspaceRepository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
        List<WorkspaceInfo> workspaces = members.stream()
                .map(m -> new WorkspaceInfo(m.getWorkspaceId(),
                        workspaceMap.containsKey(m.getWorkspaceId())
                                ? workspaceMap.get(m.getWorkspaceId()).getName() : "未知空间",
                        m.getRole()))
                .toList();
        WorkspaceMember current = members.stream()
                .filter(m -> m.getWorkspaceId().equals(currentWorkspaceId))
                .findFirst()
                .orElse(members.isEmpty() ? null : members.get(0));
        return new LoginResponse(token, user.getId(), user.getUsername(),
                current == null ? null : current.getRole(),
                current == null ? null : current.getWorkspaceId(),
                workspaces, Boolean.TRUE.equals(user.getMustChangePassword()));
    }
}
