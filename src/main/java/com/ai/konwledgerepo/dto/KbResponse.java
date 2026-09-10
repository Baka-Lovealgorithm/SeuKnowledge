package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 知识库响应。
 * <p>
 * canEdit：当前请求用户对该知识库是否具备写权限（管理员 / 创建者 / EDIT 授权），
 * 由列表接口按请求用户填充，供前端按行渲染编辑类按钮；其它接口返回全量实体时该字段为 false，
 * 前端不应据此判定（仍以列表结果为准）。该字段为响应体派生字段，不落库、不影响既有数据。
 */
public record KbResponse(Long id, String name, String description, String status, boolean archived,
                         long documentCount, LocalDateTime createdAt, String visibility, Long createdBy,
                         boolean canEdit) {

    /** 兼容既有调用：不带用户上下文时 canEdit 为 false */
    public KbResponse(Long id, String name, String description, String status, boolean archived,
                      long documentCount, LocalDateTime createdAt, String visibility, Long createdBy) {
        this(id, name, description, status, archived, documentCount, createdAt, visibility, createdBy, false);
    }

    /** 派生新的响应体并覆写 canEdit（列表按用户定制） */
    public KbResponse withCanEdit(boolean editable) {
        return new KbResponse(id, name, description, status, archived, documentCount, createdAt,
                visibility, createdBy, editable);
    }
}
