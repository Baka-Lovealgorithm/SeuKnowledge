package com.ai.konwledgerepo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 字符串列表 ↔ JSON 的读写工具（收敛各 Service 内重复的 parseList/parseDocIds/toJson 实现）。
 * <p>
 * 这几个 List&lt;String&gt; / List&lt;Long&gt; 字段（问答对同义问法、业务知识别名、抽取任务文档 id）
 * 在 DB 里以 JSON 数组文本存储。读写都遵循「宽松容错」原则：JSON 非法或类型不符一律降级，
 * 绝不因历史脏数据抛异常中断业务。降级的具体取值由调用方按字段语义选择，见下面各方法。
 */
public final class JsonLists {

    private JsonLists() {
    }

    /**
     * 读字符串列表，失败返回<b>空串列表</b>（可修改）。
     * 与 {@link #readStringsOrEmpty} 的差别仅在返回列表能否被调用方改动。
     */
    public static List<String> readStrings(String json, ObjectMapper mapper) {
        List<String> parsed = readStringsOrEmpty(json, mapper);
        return new ArrayList<>(parsed);
    }

    /** 读字符串列表，失败返回<b>不可变空列表</b>（{@code List.of()}） */
    public static List<String> readStringsOrEmpty(String json, ObjectMapper mapper) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> parsed = mapper.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 读 Long 列表（抽取任务文档 id），失败返回<b>不可变空列表</b>。
     * 历史数据可能混入非数字项，Jackson 解析失败即整体降级为空。
     */
    public static List<Long> readLongsOrEmpty(String json, ObjectMapper mapper) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            List<Long> parsed = mapper.readValue(json, new TypeReference<List<Long>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 写 JSON；空集合返回 <b>null</b>（DB 存 NULL 而非 {@code "[]"}，便于区分"未设置"与"空数组"）。
     * 序列化失败同样返回 null。
     */
    public static String writeOrNull(Collection<?> list, ObjectMapper mapper) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写 JSON；序列化失败返回 <b>{@code "[]"}</b> 兜底（用于不允许 NULL 的字段）。
     * 注意与 {@link #writeOrNull} 的失败语义不同，按字段约束选择。
     */
    public static String writeOrEmptyArray(Collection<?> list, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Object 强转为字符串列表：非 List 返回空列表（用于 JSON 节点取值等动态场景） */
    public static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return new ArrayList<>();
    }
}
