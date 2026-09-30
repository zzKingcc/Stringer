package com.zzkingcc.stringer.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 提示词配置模型（平台自有，统一前缀 {@code stringer.ai.prompt}）
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "stringer.ai.prompt")
public class PromptProperties {

    /** 公共基线：所有域共享 */
    private String base;

    /** 域差异：域 → 该域的补充提示词。留空表示只有公共基线 */
    private Map<String, String> prompts = new LinkedHashMap<>();

    /** 指定域的差异片段；未配置返回 {@code null} */
    public String domainPrompt(String domainId) {
        if (domainId == null || prompts == null) {
            return null;
        }
        return prompts.get(domainId);
    }
}
