package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 工作空间转让请求（原 WorkspaceController 内嵌 record，迁至 dto 包统一管理）。
 */
public record TransferWorkspaceRequest(@NotNull Long memberId) {
}
