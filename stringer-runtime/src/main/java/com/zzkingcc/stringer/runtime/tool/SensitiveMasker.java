package com.zzkingcc.stringer.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 敏感参数值掩码 —— 把工具调用参数里声明的敏感项替换成 {@link #MASK}。
 *
 * <p>为什么需要它：{@code @ToolAdvanced.sensitive} 声明的是"这些参数的值不要外露"，
 * 但参数原文一定会出现在两个地方 —— <b>推给客户端的工具调用事件</b>与<b>挂起等授权的审批 payload</b>。
 * 掩码只作用于这两处的展示文本；真正交给工具执行、以及回喂模型的参数始终是原值，
 * 否则工具会拿到 {@code ***} 而彻底失效。</p>
 *
 * <p>匹配是<b>递归按键名</b>的：敏感项既可能是顶层形参，也可能是 DTO 展开出的字段
 * （如 {@code args.phone}），逐层下钻才盖得住。名字命中就把整个值（标量 / 对象 / 数组）
 * 一起替换成掩码，宁可多盖也不漏。</p>
 *
 * @author zzkingcc
 */
public final class SensitiveMasker {

    private static final Logger log = LoggerFactory.getLogger(SensitiveMasker.class);

    /** 掩码文案；固定长度、不含原值任何信息 */
    public static final String MASK = "***";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SensitiveMasker() {
    }

    /**
     * 掩码参数 JSON。
     *
     * @param argumentsJson  工具调用参数（可能为 null / 空白 / 非 JSON）
     * @param sensitiveNames 需要掩码的参数名（空即原样返回）
     * @return 掩码后的 JSON 文本；无需或无法掩码时原样返回
     */
    public static String mask(String argumentsJson, Set<String> sensitiveNames) {
        if (argumentsJson == null || argumentsJson.isBlank()
                || sensitiveNames == null || sensitiveNames.isEmpty()) {
            return argumentsJson;
        }
        try {
            JsonNode masked = maskNode(MAPPER.readTree(argumentsJson), sensitiveNames);
            return MAPPER.writeValueAsString(masked);
        } catch (Exception e) {
            // 参数不是合法 JSON 时，不能因为"要脱敏"反而把原文弄丢或改坏：留痕后原样返回
            log.warn("[脱敏] 工具参数不是合法 JSON，按原样输出: {}", e.getMessage());
            return argumentsJson;
        }
    }

    private static JsonNode maskNode(JsonNode node, Set<String> sensitiveNames) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitiveNames.contains(field.getKey())) {
                    result.put(field.getKey(), MASK);
                } else {
                    result.set(field.getKey(), maskNode(field.getValue(), sensitiveNames));
                }
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode element : array) {
                result.add(maskNode(element, sensitiveNames));
            }
            return result;
        }
        return node;
    }
}
