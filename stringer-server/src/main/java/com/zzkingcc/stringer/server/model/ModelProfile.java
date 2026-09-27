package com.zzkingcc.stringer.server.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 模型档案 —— 一个 OpenAI 兼容端点的一份配置。
 *
 * <p>名字叫"档案"而不是"模型"：同一个模型可以被配成多份档案（不同 Key、不同温度、不同额度），
 * 域绑定的是档案而不是模型名。</p>
 *
 * <p><b>能力需要显式声明</b>：换了个便宜模型之后"工具静默失效"是这里最容易踩的坑，
 * 所以 {@link #capabilities()} 由使用者写出来，而不是靠试错发现。</p>
 *
 * @param alias        别名（唯一键，如 {@code default} / {@code smart}）
 * @param baseUrl      服务商地址（OpenAI 兼容，通常带 {@code /v1}）
 * @param apiKey       API Key
 * @param modelName    模型名
 * @param temperature  温度（可空 = 用服务商默认）
 * @param maxTokens    单次最大输出 token（可空 = 用服务商默认）
 * @param capabilities 能力声明：{@code streaming} / {@code tools} / {@code vision}
 * @param fallbacks    降级链：本档案不可用时依次尝试的别名（M3 生效，先只存）
 * @author zzkingcc
 */
public record ModelProfile(String alias,
                           String baseUrl,
                           String apiKey,
                           String modelName,
                           Double temperature,
                           Integer maxTokens,
                           List<String> capabilities,
                           List<String> fallbacks) {

    /** 支持流式输出（对话链路必需） */
    public static final String CAP_STREAMING = "streaming";
    /** 支持工具调用（function calling）——不支持的模型不会调任何工具 */
    public static final String CAP_TOOLS = "tools";
    /** 支持图像输入 */
    public static final String CAP_VISION = "vision";

    public ModelProfile {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    /** 必填项是否齐备 */
    public boolean isUsable() {
        return hasText(baseUrl) && hasText(apiKey) && hasText(modelName);
    }

    /**
     * 是否支持工具调用 —— 域绑定到不支持工具的档案时应当告警（而不是拒绝）。
     */
    public boolean supportsTools() {
        return capabilities.stream().anyMatch(c -> CAP_TOOLS.equalsIgnoreCase(c));
    }

    /**
     * 档案指纹 —— 客户端缓存的键。
     *
     * <p>含 Key 的哈希：<b>Key 一轮换，指纹就变</b>，于是自然拿到新实例，
     * 既不需要手工清缓存，也不会出现"改了 Key 却还在用旧连接"。</p>
     */
    public String fingerprint() {
        String raw = String.join("|",
                nz(baseUrl), nz(modelName),
                String.valueOf(temperature), String.valueOf(maxTokens),
                sha256(nz(apiKey)));
        return sha256(raw).substring(0, 16);
    }

    /** 脱敏后的 Key（供管控台展示） */
    public String maskedApiKey() {
        if (!hasText(apiKey)) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 缺失能力声明时的提示（给管控台用） */
    public String capabilityHint() {
        if (capabilities.isEmpty()) {
            return "未声明能力：无法判断是否支持工具调用，建议补上 " + CAP_TOOLS + " / " + CAP_STREAMING;
        }
        if (!supportsTools()) {
            return "未声明 " + CAP_TOOLS + "：模型将不会调用任何工具";
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能发生（JDK 必带 SHA-256）；退化为原串，宁可缓存不命中也不能抛
            return raw;
        }
    }
}
