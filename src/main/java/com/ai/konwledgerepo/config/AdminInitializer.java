package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.Workspace;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.repository.WorkspaceRepository;
import com.ai.konwledgerepo.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 内置管理员与默认工作空间初始化：
 * 1. 确保内置账号存在（用户名/初始密码见 seuknowledge.security.admin-* 配置，默认 admin/admin123）；
 * 2. 确保默认工作空间存在，admin 为其拥有者（OWNER，每空间仅一位）；
 * 3. 存量知识库回填工作空间归属（一期单工作空间）。
 */
@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private static final String DEFAULT_WORKSPACE_NAME = "默认工作空间";

    private final SysUserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final PasswordEncoder passwordEncoder;
    private final SeuSecurityProperties securityProps;

    public AdminInitializer(SysUserRepository userRepository,
                            WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository,
                            KnowledgeBaseRepository kbRepository,
                            ModelConfigRepository modelConfigRepository,
                            PasswordEncoder passwordEncoder,
                            SeuSecurityProperties securityProps) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.kbRepository = kbRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProps = securityProps;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String adminUsername = securityProps.adminUsername();
        SysUser admin = userRepository.findByUsername(adminUsername).orElseGet(() -> {
            SysUser user = new SysUser();
            user.setUsername(adminUsername);
            user.setPassword(passwordEncoder.encode(securityProps.adminPassword()));
            user.setRole(Roles.ADMIN);
            user.setEnabled(true);
            userRepository.save(user);
            log.info("已初始化内置管理员账号 {}（请尽快修改密码）", adminUsername);
            return user;
        });

        // 确保默认工作空间存在
        List<Workspace> workspaces = workspaceRepository.findAllByOrderByIdAsc();
        Workspace workspace;
        if (workspaces.isEmpty()) {
            workspace = new Workspace();
            workspace.setName(DEFAULT_WORKSPACE_NAME);
            workspace.setOwnerUserId(admin.getId());
            workspaceRepository.save(workspace);
            log.info("已创建默认工作空间「{}」，拥有者 admin", DEFAULT_WORKSPACE_NAME);
        } else {
            workspace = workspaces.get(0);
        }

        // 确保 admin 有成员记录（无记录时补为拥有者；空间已有其他拥有者时降级为管理员并告警）
        if (memberRepository.findByUserIdOrderByIdAsc(admin.getId()).isEmpty()) {
            boolean ownerExists = memberRepository.countByWorkspaceIdAndRole(workspace.getId(), WorkspaceMember.ROLE_OWNER) > 0;
            WorkspaceMember member = new WorkspaceMember();
            member.setWorkspaceId(workspace.getId());
            member.setUserId(admin.getId());
            member.setRole(ownerExists ? WorkspaceMember.ROLE_ADMIN : WorkspaceMember.ROLE_OWNER);
            memberRepository.save(member);
            if (ownerExists) {
                log.warn("默认工作空间已有其他拥有者，admin 以管理员身份加入");
            } else {
                workspace.setOwnerUserId(admin.getId());
                workspaceRepository.save(workspace);
            }
        }

        // 存量知识库回填工作空间归属（升级迁移：旧数据 workspaceId 为空）
        List<KnowledgeBase> orphanKbs = kbRepository.findByWorkspaceIdIsNull();
        if (!orphanKbs.isEmpty()) {
            for (KnowledgeBase kb : orphanKbs) {
                kb.setWorkspaceId(workspace.getId());
            }
            kbRepository.saveAll(orphanKbs);
            log.info("已为 {} 个存量知识库回填工作空间归属", orphanKbs.size());
        }

        // 存量模型配置回填工作空间归属（多工作空间隔离迁移：旧配置归默认空间）
        List<ModelConfig> orphanModels = modelConfigRepository.findByWorkspaceIdIsNull();
        if (!orphanModels.isEmpty()) {
            for (ModelConfig cfg : orphanModels) {
                cfg.setWorkspaceId(workspace.getId());
            }
            modelConfigRepository.saveAll(orphanModels);
            log.info("已为 {} 个存量模型配置回填工作空间归属", orphanModels.size());
        }
    }
}
