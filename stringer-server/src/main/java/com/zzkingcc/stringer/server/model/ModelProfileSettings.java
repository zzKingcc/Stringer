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
 *   <li><b>一个域可绑一组模型</b> —— {@code domainBindings} 是 {@code 域 → 别名列表} 的<b>有序</b>映射，
 *       整体覆盖；列表首个是当前使用的模型，其余留给多 agent / 降级；空列表即解绑；</li>
 *   <li><b>向量模型只有一个</b> —— 不在本文件里，沿用「模型设置」页那唯一一套向量配置。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Data
public class ModelProfileSettings {

    /** 域 → 可调用模型别名列表（有序；首个为当前使用；空 = 未绑定 / 无可调用） */
    private Map<String, java.util.List<String>> domainBindings = new LinkedHashMap<>();

    /** 别名 → 档案（对话 / 向量都在这里，靠 {@link ProfileData#getType()} 区分） */
    private Map<String, ProfileData> profiles = new LinkedHashMap<>();

    /**
     * 一条档案的落盘形态（与 {@link ModelProfile} 一一对应）。
     */
    @Data
    public static class ProfileData {

        /** 端点族（可多选；空 = chat） */
        private java.util.List<String> endpoints = new java.util.ArrayList<>();
        /** 输入模态（可多选） */
        private java.util.List<String> input = new java.util.ArrayList<>();
        /** 输出模态（可多选） */
        private java.util.List<String> output = new java.util.ArrayList<>();
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        /** 布尔能力：streaming / tools */
        private java.util.List<String> capabilities = new java.util.ArrayList<>();
        /** 降级链（暂只存不生效） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();

        public ModelProfile toProfile(String alias) {
            return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                    temperature, maxTokens, dimensions, capabilities, fallbacks);
        }

        public static ProfileData from(ModelProfile profile) {
            ProfileData data = new ProfileData();
            data.setEndpoints(new java.util.ArrayList<>(profile.endpoints()));
            data.setInput(new java.util.ArrayList<>(profile.input()));
            data.setOutput(new java.util.ArrayList<>(profile.output()));
            data.setBaseUrl(profile.baseUrl());
            data.setApiKey(profile.apiKey());
            data.setModelName(profile.modelName());
            data.setTemperature(profile.temperature());
            data.setMaxTokens(profile.maxTokens());
            data.setDimensions(profile.dimensions());
            data.setCapabilities(new java.util.ArrayList<>(profile.capabilities()));
            data.setFallbacks(new java.util.ArrayList<>(profile.fallbacks()));
            return data;
        }
    }
}
