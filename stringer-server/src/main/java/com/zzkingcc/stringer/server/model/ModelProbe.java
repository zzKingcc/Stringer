package com.zzkingcc.stringer.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型探测 —— 用<b>实测</b>判定一个模型的能力画像。
 *
 * <p>为什么只能实测：OpenAI 规范的 {@code GET {baseUrl}/models} 只回 {@code id}，
 * <b>没有类型/模态字段</b>；各家也没有统一的「查能力」接口。</p>
 *
 * <p><b>三个正交维度</b>（多模态模型可同时命中多个，不互斥）：</p>
 * <ol>
 *   <li><b>端点族</b>：怎么调它 —— chat / embedding / images / tts（按端点试探）；</li>
 *   <li><b>模态</b>：能吃什么 / 产出什么 —— 输入带图试出 image；输出按端点族推导；</li>
 *   <li><b>布尔能力</b>：streaming / tools。</li>
 * </ol>
 *
 * <p><b>成本与顺序</b>：对话/向量先探（便宜）；生图、语音合成会真的产出内容，所以只在
 * 前两者都不通时才兜底试探。对话探测带 {@code max_tokens=1}，费用可忽略。</p>
 *
 * <p><b>关键区别</b>：必须区分「端点不存在」（404 / 405）与「模型不适用该端点」（400 / 422）——
 * 前者<b>不能</b>用来否定能力。</p>
 *
 * @author zzkingcc
 */
@Component
public class ModelProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 1×1 透明 PNG，用于图片输入探测 */
    private static final String TINY_PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** 探测结果：三个维度 + 参数 */
    public record Result(List<String> endpoints, List<String> input, List<String> output,
                         List<String> capabilities, Integer dimension, String note) {
    }

    /** 向量探测的内部结果：端点存不存在 + 是不是向量模型 + 维度 */
    private record EmbeddingProbe(boolean endpointExists, boolean isEmbedding, Integer dimension) {
    }

    /**
     * 探测一个模型的能力画像。
     *
     * @throws IllegalStateException 所有端点都判定不了（如重排 / 语音识别 / 视频等本阶段未支持），或网络/鉴权失败
     */
    public Result probe(String baseUrl, String apiKey, String modelName) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new IllegalStateException("服务商地址、API Key、模型名都不能为空");
        }

        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> in = new LinkedHashSet<>();
        Set<String> out = new LinkedHashSet<>();
        List<String> caps = new ArrayList<>();
        Integer dimension = null;

        // 1) 向量
        EmbeddingProbe emb = probeEmbedding(baseUrl, apiKey, modelName);
        if (emb.isEmbedding()) {
            endpoints.add(ModelProfile.EP_EMBEDDING);
            in.add(ModelProfile.MOD_TEXT);
            out.add(ModelProfile.MOD_EMBEDDING);
            dimension = emb.dimension();
        }

        // 2) 对话（顺带探输入模态与布尔能力）
        if (probeChatStatus(baseUrl, apiKey, modelName) / 100 == 2) {
            endpoints.add(ModelProfile.EP_CHAT);
            in.add(ModelProfile.MOD_TEXT);
            out.add(ModelProfile.MOD_TEXT);
            if (probeVision(baseUrl, apiKey, modelName)) {
                in.add(ModelProfile.MOD_IMAGE);
            }
            if (probeStreaming(baseUrl, apiKey, modelName)) {
                caps.add(ModelProfile.CAP_STREAMING);
            }
            if (probeTools(baseUrl, apiKey, modelName)) {
                caps.add(ModelProfile.CAP_TOOLS);
            }
        }

        // 3) 都不是 → 兜底试探生图 / 语音合成（这两个会真的产出内容，所以放最后）
        if (endpoints.isEmpty() && probeImages(baseUrl, apiKey, modelName)) {
            endpoints.add(ModelProfile.EP_IMAGES);
            in.add(ModelProfile.MOD_TEXT);
            out.add(ModelProfile.MOD_IMAGE);
        }
        if (endpoints.isEmpty() && probeTts(baseUrl, apiKey, modelName)) {
            endpoints.add(ModelProfile.EP_TTS);
            in.add(ModelProfile.MOD_TEXT);
            out.add(ModelProfile.MOD_AUDIO);
        }

        if (endpoints.isEmpty()) {
            throw new IllegalStateException("探测不出任何端点：/chat/completions 与 /embeddings 都不通，"
                    + "/images/generations、/audio/speech 也未通过。"
                    + "可能是重排 / 语音识别 / 视频等本阶段尚未支持的类型。");
        }

        return new Result(List.copyOf(endpoints), List.copyOf(in), List.copyOf(out), caps, dimension,
                "端点族 " + endpoints + "；输入 " + in + "；输出 " + out
                        + "；能力 " + (caps.isEmpty() ? "（无）" : caps));
    }

    // ==================== 逐项探测 ====================

    private EmbeddingProbe probeEmbedding(String baseUrl, String apiKey, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", "ping");

        HttpResponse<String> resp = post(endpoint(baseUrl, "/embeddings"), apiKey, body);
        int code = resp.statusCode();
        if (code == 404 || code == 405) {
            return new EmbeddingProbe(false, false, null);      // 端点都不存在，不能据此判能力
        }
        if (code / 100 != 2) {
            return new EmbeddingProbe(true, false, null);       // 端点在，但该模型不是向量模型
        }
        try {
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode vec = data.get(0).path("embedding");
                if (vec.isArray()) {
                    return new EmbeddingProbe(true, true, vec.size());
                }
            }
        } catch (Exception ignored) {
            // 解析失败按"不是向量模型"处理（保守）
        }
        return new EmbeddingProbe(true, false, null);
    }

    private int probeChatStatus(String baseUrl, String apiKey, String model) {
        return post(endpoint(baseUrl, "/chat/completions"), apiKey, chatBody(model)).statusCode();
    }

    private boolean probeStreaming(String baseUrl, String apiKey, String model) {
        ObjectNode body = chatBody(model);
        body.put("stream", true);
        HttpResponse<String> resp = post(endpoint(baseUrl, "/chat/completions"), apiKey, body);
        if (resp.statusCode() / 100 != 2) {
            return false;
        }
        String text = resp.body() == null ? "" : resp.body();
        return text.contains("\"delta\"") || text.contains("data:");
    }

    private boolean probeTools(String baseUrl, String apiKey, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        /* 引导语要"明确要求调用工具"，否则模型可能只用文字回答 → 假阴性 */
        body.putArray("messages").addObject()
                .put("role", "user").put("content", "北京天气怎么样？请调用工具查询。");

        ObjectNode fn = body.putArray("tools").addObject().put("type", "function").putObject("function");
        fn.put("name", "get_weather");
        fn.put("description", "查询指定城市的天气");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        params.putObject("properties").putObject("city").put("type", "string");
        params.putArray("required").add("city");

        /* max_tokens 必须给足：给 1 会把 tool_calls 的 JSON 截断，服务商返回空 choices → 假阴性 */
        body.put("max_tokens", 64);

        HttpResponse<String> resp = post(endpoint(baseUrl, "/chat/completions"), apiKey, body);
        if (resp.statusCode() / 100 != 2) {
            return false;
        }
        String text = resp.body() == null ? "" : resp.body();
        return text.contains("tool_calls");
    }

    /** 图片输入（vision）：发一个带图的消息，能 2xx 就是支持 */
    private boolean probeVision(String baseUrl, String apiKey, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode content = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "hi");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", TINY_PNG);
        body.put("max_tokens", 1);

        return post(endpoint(baseUrl, "/chat/completions"), apiKey, body).statusCode() / 100 == 2;
    }

    /** 生图：会真的产出一张图（有成本），所以只在兜底时才调 */
    private boolean probeImages(String baseUrl, String apiKey, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", "a red dot");
        body.put("n", 1);
        body.put("size", "256x256");

        int code = post(endpoint(baseUrl, "/images/generations"), apiKey, body).statusCode();
        return code / 100 == 2;
    }

    /** 语音合成：会真的产出一段音频（有成本），所以只在兜底时才调 */
    private boolean probeTts(String baseUrl, String apiKey, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", "hi");
        body.put("voice", "alloy");

        int code = post(endpoint(baseUrl, "/audio/speech"), apiKey, body).statusCode();
        return code / 100 == 2;
    }

    // ==================== 基础设施 ====================

    private ObjectNode chatBody(String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", "ping");
        body.put("max_tokens", 1);
        return body;
    }

    /** 容忍地址末尾多打斜杠 */
    private static String endpoint(String baseUrl, String path) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s + path;
    }

    private HttpResponse<String> post(String url, String apiKey, ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型探测被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败: " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
