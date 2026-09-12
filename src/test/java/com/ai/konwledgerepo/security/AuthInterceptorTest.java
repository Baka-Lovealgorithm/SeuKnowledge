package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.controller.PromptTemplateController;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 平台级接口必须使用 sys_user.role，不得由工作空间角色越权。 */
class AuthInterceptorTest {

    private static final long USER_ID = 7L;

    private TokenService tokenService;
    private WorkspaceMemberRepository memberRepository;
    private SysUserRepository userRepository;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        userRepository = mock(SysUserRepository.class);
        // 真实 RedisCacheService + 裸 mock 的 RedisTemplate：缓存读写全部 fail-open 降级（getString 返回 empty），
        // 使请求走「缓存 miss → DB 回源」路径；缓存命中路径在 dedicated 测试里单独 stub
        interceptor = new AuthInterceptor(tokenService, memberRepository, userRepository, new ObjectMapper(),
                new RedisCacheService(mock(StringRedisTemplate.class), new ObjectMapper()),
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
        when(tokenService.resolve("valid-token")).thenReturn(Optional.of(USER_ID));
    }

    @Test
    void promptTemplateController_requiresPlatformAdminInsteadOfWorkspaceAdmin() {
        assertTrue(AnnotatedElementUtils.findMergedAnnotation(PromptTemplateController.class,
                PlatformAdminOnly.class) != null);
        assertNull(AnnotatedElementUtils.findMergedAnnotation(PromptTemplateController.class, AdminOrAbove.class));
    }

    @Test
    void platformEndpoint_workspaceOwnerOrAdminRoleCannotBypass() throws Exception {
        SysUser member = user("MEMBER", true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(member));
        MockHttpServletRequest request = authorizedRequest();
        request.addHeader("X-Workspace-Id", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, platformHandler());

        assertFalse(allowed);
        assertTrue(response.getContentAsString().contains("需要平台管理员权限"));
        // 平台接口应在成员关系解析之前拒绝，工作空间 OWNER/ADMIN 无法参与授权。
        verifyNoInteractions(memberRepository);
    }

    @Test
    void platformEndpoint_sysAdminAllowedWithoutWorkspaceMembership() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(Roles.ADMIN, true)));
        MockHttpServletRequest request = authorizedRequest();
        request.addHeader("X-Workspace-Id", "999");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), platformHandler());

        assertTrue(allowed);
        assertTrue(USER_ID == (Long) request.getAttribute("userId"));
        assertNull(request.getAttribute("workspaceId"));
        verifyNoInteractions(memberRepository);
    }

    private MockHttpServletRequest authorizedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/prompts/chat-only");
        request.addHeader("Authorization", "Bearer valid-token");
        return request;
    }

    /** 回归锁：被禁用用户即使持有有效 token 也必须在所有接口前被拒（此前仅平台接口分支查 enabled） */
    @Test
    void disabledUser_validToken_rejectedBeforeAnythingElse() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(Roles.ADMIN, false)));
        MockHttpServletRequest request = authorizedRequest();
        request.addHeader("X-Workspace-Id", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, platformHandler());

        assertFalse(allowed, "禁用用户的存量 token 不得继续访问");
        assertTrue(response.getContentAsString().contains("禁用"));
        verifyNoInteractions(memberRepository);
    }

    /** 回归锁：启用状态经 60s Redis 缓存复核，命中后 enabled 复核不再查 DB（平台管理员角色分支仍按既有语义直查 DB） */
    @Test
    void enabledCheck_usesRedisCache_beforeDb() throws Exception {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seuknowledge:user:enabled:" + USER_ID)).thenReturn("1");
        AuthInterceptor cached = new AuthInterceptor(tokenService, memberRepository, userRepository,
                new ObjectMapper(), new RedisCacheService(template, new ObjectMapper()),
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(Roles.ADMIN, true)));

        MockHttpServletRequest request = authorizedRequest();
        request.addHeader("X-Workspace-Id", "999");
        assertTrue(cached.preHandle(request, new MockHttpServletResponse(), platformHandler()));
        assertTrue(cached.preHandle(request, new MockHttpServletResponse(), platformHandler()));

        // 两次请求各只有 isPlatformAdmin 的 1 次 DB 查询；若无缓存，enabled 复核会再各加 1 次（共 4 次）
        verify(userRepository, times(2)).findById(USER_ID);
    }

    private HandlerMethod platformHandler() throws NoSuchMethodException {
        Method method = PlatformEndpoint.class.getMethod("update");
        return new HandlerMethod(new PlatformEndpoint(), method);
    }

    private static SysUser user(String role, boolean enabled) {
        SysUser user = new SysUser();
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }

    @PlatformAdminOnly
    static class PlatformEndpoint {
        public void update() {
        }
    }
}
