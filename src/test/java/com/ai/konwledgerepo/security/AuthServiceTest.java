package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.LoginRequest;
import com.ai.konwledgerepo.dto.LoginResponse;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.Workspace;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证业务测试：自助修改密码（旧密码校验 / 新密码策略 / 两次一致 / 清除须改密标记）与
 * 登录响应携带 mustChangePassword（管理员重置后强制改密）。
 */
class AuthServiceTest {

    private SysUserRepository userRepository;
    private WorkspaceMemberRepository memberRepository;
    private WorkspaceRepository workspaceRepository;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttemptService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(SysUserRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttemptService = mock(LoginAttemptService.class);
        service = new AuthService(userRepository, mock(TokenService.class), memberRepository,
                workspaceRepository, passwordEncoder, loginAttemptService);
    }

    private SysUser user(String passwordHash, boolean mustChange) {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setUsername("admin");
        u.setPassword(passwordHash);
        u.setRole("ADMIN");
        u.setEnabled(true);
        u.setMustChangePassword(mustChange);
        return u;
    }

    // ===== changePassword =====

    @Test
    void changePassword_success_rehashesAndClearsFlag() {
        SysUser u = user("$old$", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("旧密码", "$old$")).thenReturn(true);
        when(passwordEncoder.encode("新密码abc123")).thenReturn("$new$");

        service.changePassword(1L, "旧密码", "新密码abc123", "新密码abc123");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).save(captor.capture());
        assertEquals("$new$", captor.getValue().getPassword());
        assertFalse(captor.getValue().getMustChangePassword());
    }

    @Test
    void changePassword_wrongOldPassword_rejected() {
        SysUser u = user("$old$", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(eq("错的"), anyString())).thenReturn(false);

        assertThrows(BizException.class,
                () -> service.changePassword(1L, "错的", "新密码abc123", "新密码abc123"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_confirmMismatch_rejected() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user("$old$", false)));

        assertThrows(BizException.class,
                () -> service.changePassword(1L, "旧密码", "新密码abc123", "不一致"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_weakNewPassword_rejected() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user("$old$", false)));
        when(passwordEncoder.matches("旧密码", "$old$")).thenReturn(true);

        // 缺数字 / 缺字母 / 不足 8 位
        assertThrows(BizException.class, () -> service.changePassword(1L, "旧密码", "abcdefgh", "abcdefgh"));
        assertThrows(BizException.class, () -> service.changePassword(1L, "旧密码", "12345678", "12345678"));
        assertThrows(BizException.class, () -> service.changePassword(1L, "旧密码", "ab12", "ab12"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_clearsResetFlag() {
        SysUser u = user("$old$", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("临时密码x9", "$old$")).thenReturn(true);
        when(passwordEncoder.encode("新密码abc123")).thenReturn("$new$");

        service.changePassword(1L, "临时密码x9", "新密码abc123", "新密码abc123");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).save(captor.capture());
        assertFalse(captor.getValue().getMustChangePassword(), "被重置的用户改密后须清除强制改密标记");
    }

    // ===== 登录响应携带 mustChangePassword =====

    @Test
    void login_afterAdminReset_respondsMustChangePassword() {
        SysUser u = user("$old$", true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("raw", "$old$")).thenReturn(true);
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(1L);
        m.setUserId(1L);
        m.setRole("ADMIN");
        when(memberRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(m));
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setName("默认工作空间");
        ws.setOwnerUserId(1L);
        when(workspaceRepository.findAllByOrderByIdAsc()).thenReturn(List.of(ws));

        LoginResponse resp = service.login(new LoginRequest("admin", "raw"));

        assertTrue(resp.mustChangePassword());
    }

    @Test
    void login_normalUser_respondsMustChangeFalse() {
        SysUser u = user("$old$", false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("raw", "$old$")).thenReturn(true);
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(1L);
        m.setUserId(1L);
        m.setRole("ADMIN");
        when(memberRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(m));
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setName("默认工作空间");
        ws.setOwnerUserId(1L);
        when(workspaceRepository.findAllByOrderByIdAsc()).thenReturn(List.of(ws));

        LoginResponse resp = service.login(new LoginRequest("admin", "raw"));

        assertFalse(resp.mustChangePassword());
    }
}
