package com.zzkingcc.stringer.server.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型档案的落盘结构（{@code config/models.json}）。
 *
 * <p>三条规则体现在结构里：</p>
 * <ul>
 *   <li><b>档案不自动绑定任何域</b> —— {@code domainBindings} 只能由管控台手工填写；</li>
 *   <li><b>一个域只绑一个模型</b> —— {@code domainBindings} 是 {@code 域 → 别名} 的单值映射，
 *       再次设置即覆盖（Map 的 put 语义）；</li>
 *   <li><b>向量模型只有一个</b> —— 不在本文件里，沿用「模型设置」页那唯一一套向量配置。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Data
public class ModelProfileSettings {

    /**
     * 未绑定模型的域使用哪个别名。
     *
     * <p>默认 {@code default} ＝ 管控台「模型设置」页那一套（由 {@code LlmModelHolder} 承载），
     * 所以"一个域都没绑"与升级前的行为完全一致。</p>
     */
    private String defaultAlias = ModelProfileRegistry.BUILTIN_DEFAULT;

    /** 域 → 别名（单值，后设覆盖） */
    private Map<String, String> domainBindings = new LinkedHashMap<>();

    /** 别名 → 档案 */
    private Map<String, ProfileData> chatProfiles = new LinkedHashMap<>();

    /**
     * 一条档案的落盘形态（与 {@link ModelProfile} 一一对应）。
     */
    @Data
    public static class ProfileData {

        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /** 能力声明：streaming / tools / vision */
        private java.util.List<String> capabilities = new java.util.ArrayList<>();
        /** 降级链（暂只存不生效） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();

        public ModelProfile toProfile(String alias) {
            return new ModelProfile(alias, baseUrl, apiKey, modelName,
                    temperature, maxTokens, capabilities, fallbacks);
        }

        public static ProfileData from(ModelProfile profile) {
            ProfileData data = new ProfileData();
            data.setBaseUrl(profile.baseUrl());
            data.setApiKey(profile.apiKey());
            data.setModelName(profile.modelName());
            data.setTemperature(profile.temperature());
            data.setMaxTokens(profile.maxTokens());
            data.setCapabilities(new java.util.ArrayList<>(profile.capabilities()));
            data.setFallbacks(new java.util.ArrayList<>(profile.fallbacks()));
            return data;
        }
    }
}
