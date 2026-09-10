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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        interceptor = new AuthInterceptor(tokenService, memberRepository, userRepository, new ObjectMapper(),
                mock(RedisCacheService.class), new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
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
