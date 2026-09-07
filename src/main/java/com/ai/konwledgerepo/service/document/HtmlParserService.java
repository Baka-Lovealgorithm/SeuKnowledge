package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地 HTML 备用解析器：仅在 {@code KB_HTML_LOCAL_PARSER_ENABLED=true} 时由文档分发器选用。
 * 默认 HTML 使用 LlamaParse，以获得更完整的 Markdown 和表格结构。
 */
@Service
public class HtmlParserService {

    private static final Set<String> NOISE_TAGS = Set.of(
            "script", "style", "noscript", "template", "svg", "canvas", "iframe", "form", "button", "input");

    private static final String NOISE_SELECTORS = String.join(", ",
            "nav", "header:not(.header)", "footer", "aside",
            "#top", "#side-nav", "#nav-tree", "#nav-path", "#MSearchSelectWindow", "#MSearchResultsWindow",
            ".navpath", ".footer", ".tabs", ".tablist", ".summary", ".memnav", ".memitem", ".directory",
            ".lineno", ".ui-resizable-handle");

    /** 读取 HTML、提取正文并按既有文本规则分块。HTML 没有真实页码，因此页码统一为 0。 */
    public List<ChunkPiece> parse(Path path, int chunkSize, int chunkOverlap) {
        try {
            String markdown = toMarkdown(Jsoup.parse(path.toFile(), null));
            if (markdown.isBlank()) {
                throw new BizException("HTML 未提取到可索引的正文内容: " + path.getFileName());
            }
            return ChunkSplitter.split(markdown, 0, chunkSize, chunkOverlap);
        } catch (IOException e) {
            throw new BizException("HTML 文档读取失败: " + e.getMessage());
        }
    }

    /** HTML 转 Markdown 风格纯文本。包可见以便单元测试覆盖 Doxygen 与普通 HTML。 */
    static String toMarkdown(String html) {
        return toMarkdown(Jsoup.parse(html == null ? "" : html));
    }

    private static String toMarkdown(Document doc) {
        doc.select(String.join(", ", NOISE_TAGS)).remove();
        doc.select(NOISE_SELECTORS).remove();

        Element root = firstNonEmpty(doc.select(".contents"));
        Element doxygenTitle = root == null ? null : doc.selectFirst(".header .title");
        if (root == null) {
            root = firstNonEmpty(doc.select("main, article, [role=main]"));
        }
        if (root == null) {
            root = doc.body();
        }
        if (root == null) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        if (doxygenTitle != null && !doxygenTitle.text().isBlank()) {
            appendBlock(out, "# " + doxygenTitle.text());
        }
        appendNode(root, out, 0);
        return normalize(out.toString());
    }

    private static Element firstNonEmpty(Elements candidates) {
        for (Element candidate : candidates) {
            if (!candidate.text().isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendNode(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode textNode) {
            appendText(out, textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }

        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (NOISE_TAGS.contains(tag) || isNoise(element)) {
            return;
        }
        if ("img".equals(tag)) {
            String alt = element.attr("alt").trim();
            if (!alt.isBlank()) {
                appendBlock(out, "[图片: " + alt + "]");
            }
            return;
        }
        if ("br".equals(tag)) {
            appendNewline(out);
            return;
        }
        if (tag.matches("h[1-6]")) {
            int level = tag.charAt(1) - '0';
            appendBlock(out, "#".repeat(level) + " " + element.text());
            return;
        }
        if ("pre".equals(tag)) {
            String code = element.text().strip();
            if (!code.isBlank()) {
                appendBlock(out, "```\n" + code + "\n```");
            }
            return;
        }
        if ("table".equals(tag)) {
            appendTable(element, out);
            return;
        }
        if ("ul".equals(tag) || "ol".equals(tag)) {
            for (Element item : element.children()) {
                if (!"li".equals(item.tagName())) {
                    continue;
                }
                String prefix = "ol".equals(tag) ? "1. " : "- ";
                appendBlock(out, "  ".repeat(Math.max(0, listDepth)) + prefix + ownText(item));
                for (Element child : item.children()) {
                    if ("ul".equals(child.tagName()) || "ol".equals(child.tagName())) {
                        appendNode(child, out, listDepth + 1);
                    }
                }
            }
            return;
        }
        if ("li".equals(tag)) {
            appendBlock(out, "- " + ownText(element));
            return;
        }

        boolean block = isBlock(tag);
        if (block) {
            appendNewline(out);
        }
        for (Node child : element.childNodes()) {
            appendNode(child, out, listDepth);
        }
        if (block) {
            appendNewline(out);
        }
    }

    private static boolean isNoise(Element element) {
        String idAndClasses = (element.id() + " " + element.className()).toLowerCase(Locale.ROOT);
        return idAndClasses.contains("nav") || idAndClasses.contains("search") || idAndClasses.contains("footer")
                || idAndClasses.contains("lineno");
    }

    private static boolean isBlock(String tag) {
        return switch (tag) {
            case "address", "blockquote", "div", "dl", "dt", "dd", "figure", "figcaption", "p", "section" -> true;
            default -> false;
        };
    }

    private static void appendTable(Element table, StringBuilder out) {
        boolean wroteRow = false;
        for (Element row : table.select("tr")) {
            List<String> cells = row.children().stream()
                    .filter(cell -> "th".equals(cell.tagName()) || "td".equals(cell.tagName()))
                    .map(Element::text)
                    .toList();
            if (cells.isEmpty()) {
                continue;
            }
            appendBlock(out, "| " + String.join(" | ", cells) + " |");
            if (!wroteRow && row.children().stream().anyMatch(cell -> "th".equals(cell.tagName()))) {
                appendBlock(out, "| " + "--- | ".repeat(cells.size()));
            }
            wroteRow = true;
        }
    }

    private static String ownText(Element element) {
        Element copy = element.clone();
        copy.select("ul, ol").remove();
        return copy.text();
    }

    private static void appendText(StringBuilder out, String text) {
        String normalized = text == null ? "" : text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return;
        }
        if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.append(' ');
        }
        out.append(normalized);
    }

    private static void appendBlock(StringBuilder out, String text) {
        appendNewline(out);
        out.append(text == null ? "" : text.strip());
        appendNewline(out);
    }

    private static void appendNewline(StringBuilder out) {
        if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
