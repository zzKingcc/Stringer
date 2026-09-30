package com.zzkingcc.stringer.server.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 域提示词设置（落盘 {@code config/prompts.json}）
 * @author zzkingcc
 */
public class DomainSettings {

    /** 公共基线：所有域共享 */
    private String base = "";

    /** 域 → 该域的差异提示词 */
    private Map<String, String> prompts = new LinkedHashMap<>();

    public String getBase() {
        return base;
    }

    public void setBase(String base) {
        this.base = base == null ? "" : base;
    }

    public Map<String, String> getPrompts() {
        return prompts;
    }

    public void setPrompts(Map<String, String> prompts) {
        this.prompts = prompts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prompts);
    }

    /** 指定域的差异片段；未配置返回 {@code null} */
    public String domainPrompt(String domainId) {
        return prompts.get(domainId);
    }
}
