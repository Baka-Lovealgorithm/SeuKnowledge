package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 创建抽取任务请求。
 */
public record ExtractTaskCreateRequest(
        @NotNull(message = "知识库不能为空")
        Long kbId,

        @NotEmpty(message = "请选择要抽取的文档")
        List<Long> docIds,

        @NotBlank(message = "抽取类型不能为空")
        @Pattern(regexp = "BUSINESS|QA|BOTH", message = "抽取类型仅支持 BUSINESS / QA / BOTH")
        String extractType) {
}
