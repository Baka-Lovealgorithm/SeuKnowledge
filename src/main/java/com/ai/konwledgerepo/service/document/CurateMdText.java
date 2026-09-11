package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.entity.DocumentCurate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 整篇 md 的文本拼装口径——<b>唯一来源</b>。
 * <p>
 * 页标记格式 {@code \n<!-- PAGE n -->\n + content} 同时被三处使用：初洗编辑器回显
 * （{@code DocumentCurateService.getMd}）、编辑保存的切页
 * （{@code DocumentCurateService.splitPages} 的反向操作）、以及对象存储里的 md 镜像。
 * 集中到本类是为了杜绝「镜像内容与编辑器回显不一致」这类格式漂移。
 */
final class CurateMdText {

    private CurateMdText() {
    }

    /** 追加单页（拼装格式的唯一实现）。 */
    static void appendPage(StringBuilder sb, int pageNum, String content) {
        sb.append("\n<!-- PAGE ").append(pageNum).append(" -->\n").append(content);
    }

    /** 逐页（{@code PageMarkdown}，解析链路来源）拼成整篇 md：按页码升序，跳过空页。 */
    static String assemblePages(List<LlamaParseService.PageMarkdown> pages) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }
        List<LlamaParseService.PageMarkdown> ordered = new ArrayList<>(pages);
        ordered.sort(Comparator.comparingInt(LlamaParseService.PageMarkdown::pageNumber));
        StringBuilder sb = new StringBuilder();
        for (LlamaParseService.PageMarkdown page : ordered) {
            if (page.markdown() == null || page.markdown().isBlank()) {
                continue;
            }
            appendPage(sb, page.pageNumber(), page.markdown());
        }
        return sb.toString();
    }

    /** 逐页（{@code DocumentCurate} 行，初洗链路来源）拼成整篇 md：按页码升序。 */
    static String assembleRows(List<DocumentCurate> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        List<DocumentCurate> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(DocumentCurate::getPageNum));
        StringBuilder sb = new StringBuilder();
        for (DocumentCurate row : ordered) {
            appendPage(sb, row.getPageNum(), row.getContent());
        }
        return sb.toString();
    }
}
