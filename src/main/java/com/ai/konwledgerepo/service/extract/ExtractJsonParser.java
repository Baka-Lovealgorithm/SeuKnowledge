package com.ai.konwledgerepo.service.extract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抽取 LLM 响应解析：从模型输出中提取 JSON 数组（容忍前后缀噪声文本），
 * 供业务知识抽取器与问答对抽取器共用。
 */
@Component
public class ExtractJsonParser {

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*]", Pattern.DOTALL);

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public ExtractJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析模型输出中的 JSON 数组；无数组或解析失败返回空列表（单 chunk 跳过，不阻断整文档） */
    public List<Map<String, Object>> parseArray(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
            if (matcher.find()) {
                return objectMapper.readValue(matcher.group(), new TypeReference<List<Map<String, Object>>>() {
                });
            }
        } catch (Exception ignored) {
            // 单 chunk 抽取失败，跳过继续
        }
        return List.of();
    }

    /** 解析模型输出中的 JSON 对象（如 answer-verify 的 {"score":85,"missing":"…"}）；无对象或解析失败返回空 Map */
    public Map<String, Object> parseObject(String response) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        try {
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(response);
            if (matcher.find()) {
                return objectMapper.readValue(matcher.group(), new TypeReference<Map<String, Object>>() {
                });
            }
        } catch (Exception ignored) {
            // 解析失败返回空 Map，调用方按既有容错逻辑回退
        }
        return Map.of();
    }
}
