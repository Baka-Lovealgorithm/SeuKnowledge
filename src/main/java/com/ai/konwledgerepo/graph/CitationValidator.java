package com.ai.konwledgerepo.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案引用编号校验（静态纯函数）：解析答案中的 [n] 标记，识别越界引用
 * （编号 &lt;1 或 &gt; 证据条数），并提供"移除越界标记、保留正文"的修正。
 * 用于保证答案中的引用编号与 REFS 列表一致（生成端程序化兜底，不依赖 LLM）。
 */
public final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private CitationValidator() {
    }

    /** 提取答案中全部引用编号（无引用返回空数组） */
    public static int[] extract(String answer) {
        if (answer == null || answer.isBlank()) {
            return new int[0];
        }
        Matcher matcher = CITATION.matcher(answer);
        List<Integer> refs = new ArrayList<>();
        while (matcher.find()) {
            refs.add(Integer.parseInt(matcher.group(1)));
        }
        return refs.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 最大引用编号；无引用返回 0 */
    public static int maxCitation(String answer) {
        int[] refs = extract(answer);
        int max = 0;
        for (int r : refs) {
            if (r > max) {
                max = r;
            }
        }
        return max;
    }

    /** 是否存在越界引用（编号 &lt;1 或 &gt; refsCount） */
    public static boolean hasOutOfRange(String answer, int refsCount) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            if (n < 1 || n > refsCount) {
                return true;
            }
        }
        return false;
    }

    /**
     * 移除越界引用标记、保留正文与合法标记；无越界时返回原串（不复制）。
     */
    public static String stripOutOfRange(String answer, int refsCount) {
        if (answer == null || answer.isBlank() || !hasOutOfRange(answer, refsCount)) {
            return answer;
        }
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder sb = new StringBuilder(answer.length());
        int last = 0;
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            if (n < 1 || n > refsCount) {
                sb.append(answer, last, matcher.start());
                last = matcher.end();
            }
        }
        if (last == 0) {
            return answer;
        }
        sb.append(answer, last, answer.length());
        return sb.toString();
    }
}
