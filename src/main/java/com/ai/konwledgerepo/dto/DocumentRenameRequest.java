package com.ai.konwledgerepo.dto;

/**
 * 文档重命名请求（只改 {@code kb_document.file_name} 与各处冗余引用名，不重解析、不动磁盘文件）。
 * <p>
 * 校验刻意放在服务层（{@code DocumentService.rename}）而不是用 bean validation：
 * 需要给出"不允许改扩展名""同知识库已存在同名"这类可操作的中文提示，
 * 与本项目其余文档链路的手工校验口径保持一致。
 *
 * @param fileName 新文件名（含扩展名，扩展名必须与现有 fileType 一致）
 */
public record DocumentRenameRequest(String fileName) {
}
