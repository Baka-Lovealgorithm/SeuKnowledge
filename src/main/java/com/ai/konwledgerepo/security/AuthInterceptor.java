package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.LogContext;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 登录态与角色权限拦截器（多工作空间）：
 * 1. 校验 Authorization: Bearer <token>，未登录 401；
 * 2. 加载用户全部工作空间成员记录（多空间可并存），经 Redis 缓存
 *    （seuknowledge:member:list:{userId}，TTL 300s），miss 回源 DB 回填；
 *    角色/归属变更由 WorkspaceService 写路径按模式失效；
 * 3. 当前工作空间：优先取请求头 X-Workspace-Id（须为该用户成员空间，否则 403；
 *    /api/auth/me 特例回退第一个成员保证前端自愈），缺省回退第一个成员关系；
 * 4. 无任何成员关系的用户：仅放行 /api/auth/me 与 POST /api/workspace（创建工作空间），
 *    其余接口 403；
 * 5. 校验 HandlerMethod 上的 {@link RequireRole} 注解（方法级优先于类级），角色不符 403。
 * 注入 request 属性：userId / workspaceId / role。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final WorkspaceMemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final RedisCacheService redisCacheService;
    private final Duration memberTtl;

    public AuthInterceptor(TokenService tokenService,
                           WorkspaceMemberRepository memberRepository,
                           ObjectMapper objectMapper,
                           RedisCacheService redisCacheService,
                           SeuCacheProperties cacheProps) {
        this.tokenService = tokenService;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
        this.redisCacheService = redisCacheService;
        this.memberTtl = Duration.ofSeconds(cacheProps.memberTtlSeconds());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 请求级日志上下文：requestId（透传 X-Request-Id 或生成 UUID），随请求贯穿全链路
        LogContext.initRequestId(request.getHeader("X-Request-Id"));
        // 静态资源与跨域预检不拦截
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        Long userId = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            Optional<Long> resolved = tokenService.resolve(token);
            if (resolved.isPresent()) {
                userId = resolved.get();
            }
        }
        if (userId == null) {
            // 未登录：HTTP 401，前端据此跳转登录页
            LogContext.clear();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ErrorCodes.UNAUTHORIZED, "未登录或会话失效")));
            return false;
        }
        // 用户全部工作空间成员关系（Redis 列表缓存优先，miss 回源 DB 回填；空列表不缓存）
        List<WorkspaceMember> members = cachedMembers(userId);
        if (members.isEmpty()) {
            // 无任何工作空间：仅放行 /me 与创建工作空间
            String path = request.getRequestURI();
            boolean allow = "/api/auth/me".equals(path)
                    || ("/api/workspace".equals(path) && "POST".equalsIgnoreCase(request.getMethod()));
            if (allow) {
                request.setAttribute("userId", userId);
                request.setAttribute("workspaceId", null);
                request.setAttribute("role", null);
                LogContext.setUserContext(userId, null);
                return true;
            }
            LogContext.clear();
            return reject(response, 403, "当前账号未加入任何工作空间");
        }
        // 当前工作空间：X-Workspace-Id 头优先，缺省回退第一个成员关系
        WorkspaceMember current = resolveCurrent(request, members);
        if (current == null) {
            LogContext.clear();
            return reject(response, 403, "无权访问该工作空间");
        }
        request.setAttribute("userId", userId);
        request.setAttribute("workspaceId", current.getWorkspaceId());
        request.setAttribute("role", current.getRole());
        LogContext.setUserContext(userId, current.getWorkspaceId());

        // 角色校验（方法级注解优先于类级；AnnotatedElementUtils 支持元注解，如 @EditorOrAbove）
        RequireRole required = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
        }
        if (required != null && !Arrays.asList(required.value()).contains(current.getRole())) {
            LogContext.clear();
            return reject(response, 403, "无权限执行该操作（需要角色: " + String.join("/", required.value()) + "）");
        }
        return true;
    }

    /** 请求结束清理日志上下文（防线程复用污染；注意 preHandle 返回 false 的路径已在拒绝分支内联清理） */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LogContext.clear();
    }

    /** 解析当前工作空间：头指定（非成员且非 /me → null 拒绝）；缺省 → 第一个成员关系 */
    private WorkspaceMember resolveCurrent(HttpServletRequest request, List<WorkspaceMember> members) {
        String headerWs = request.getHeader("X-Workspace-Id");
        if (headerWs != null && !headerWs.isBlank()) {
            Long wsId;
            try {
                wsId = Long.valueOf(headerWs.trim());
            } catch (NumberFormatException e) {
                return "/api/auth/me".equals(request.getRequestURI()) ? members.get(0) : null;
            }
            Optional<WorkspaceMember> byHeader = members.stream()
                    .filter(m -> m.getWorkspaceId().equals(wsId))
                    .findFirst();
            if (byHeader.isEmpty()) {
                // /me 特例：回退第一个成员，保证前端自愈（如上次空间已被删除/移出）
                return "/api/auth/me".equals(request.getRequestURI()) ? members.get(0) : null;
            }
            return byHeader.get();
        }
        return members.get(0);
    }

    /** 读取用户成员列表：Redis 缓存 → DB 回源回填（仅缓存非空列表，避免负缓存污染） */
    private List<WorkspaceMember> cachedMembers(Long userId) {
        Optional<List<MemberContext>> cached = redisCacheService.get(RedisKeys.memberList(userId),
                new TypeReference<List<MemberContext>>() {
                });
        if (cached.isPresent()) {
            return cached.get().stream().map(ctx -> {
                WorkspaceMember m = new WorkspaceMember();
                m.setWorkspaceId(ctx.workspaceId());
                m.setRole(ctx.role());
                return m;
            }).toList();
        }
        List<WorkspaceMember> members = memberRepository.findByUserIdOrderByIdAsc(userId);
        if (!members.isEmpty()) {
            redisCacheService.set(RedisKeys.memberList(userId),
                    members.stream().map(MemberContext::from).toList(), memberTtl);
        }
        return members;
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
        return false;
    }
}
