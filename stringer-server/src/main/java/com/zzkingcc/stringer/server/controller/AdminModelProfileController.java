package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.BaseException;
import com.zzkingcc.stringer.server.model.ModelProbe;
import com.zzkingcc.stringer.server.model.ModelProfile;
import com.zzkingcc.stringer.server.model.ModelProfileRegistry;
import com.zzkingcc.stringer.server.model.ModelProfileStore;
import com.zzkingcc.stringer.server.settings.LlmFailureDescriber;
import com.zzkingcc.stringer.server.settings.LlmModelHolder;
import com.zzkingcc.stringer.server.settings.LlmSettings;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：模型档案与「域 → 档案」绑定。
 *
 * <p>三条产品规则的落点：</p>
 * <ol>
 *   <li>档案建完<b>不自动绑任何域</b>，绑定只能通过本接口显式设置；</li>
 *   <li>一个域绑一组<b>可调用模型</b>（有序，整体覆盖）—— 首个是当前使用的模型，
 *       其余留给多 agent / 降级；</li>
 *   <li>没有内置 {@code default} 模型：对话只走用户自建档案，域必须先显式配置模型，
 *       未配置即"无可调用"。</li>
 * </ol>
 *
 * <p>路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。</p>
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminModelProfileController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelProfileController.class);

    private final ModelProfileRegistry registry;
    private final ModelProfileStore store;
    private final LlmModelHolder holder;
    private final ModelProbe probe;

    public AdminModelProfileController(ModelProfileRegistry registry,
                                       ModelProfileStore store,
                                       LlmModelHolder holder,
                                       ModelProbe probe) {
        this.registry = registry;
        this.store = store;
        this.holder = holder;
        this.probe = probe;
    }

    /**
     * 档案全貌：档案列表（Key 脱敏）+ 域绑定 + 默认别名 + 落盘位置。
     */
    @GetMapping("/model-profiles")
    public Map<String, Object> list() {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (ModelProfile profile : registry.profiles()) {
            profiles.add(profileView(profile));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("profiles", profiles);
        body.put("domainBindings", registry.domainBindings());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 新建或整体覆盖一个档案（同名即覆盖）。
     *
     * <p>{@code apiKey} <b>必填</b> —— 管控台只回填脱敏后的 Key，所以编辑时也必须重新填写。</p>
     */
    @PostMapping("/model-profiles")
    public Map<String, Object> save(@RequestBody ProfileBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        if (body.getApiKey() == null || body.getApiKey().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "API Key 不能为空");
        }
        String alias = body.getAlias().trim();
        String apiKey = body.getApiKey().trim();

        ModelProfile profile = new ModelProfile(alias, body.getEndpoints(), body.getInput(), body.getOutput(),
                body.getBaseUrl(), apiKey, body.getModelName(), body.getTemperature(), body.getMaxTokens(),
                body.getDimensions(), body.getCapabilities(), body.getFallbacks());

        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        return result("saved", alias);
    }

    /**
     * 探测：用<b>实测</b>判定一个模型的能力画像（端点族 / 输入模态 / 输出模态 / 布尔能力）。
     *
     * <p>与「测试连接」的区别：测试是"这个<b>已保存</b>的档案能不能用"，探测是"这个<b>模型是什么</b>"。</p>
     */
    @PostMapping("/model-profiles/probe")
    public Map<String, Object> probeModel(@RequestBody(required = false) ProfileBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        return probeBody(runProbe(body.getBaseUrl(), body.getApiKey(), body.getModelName()));
    }

    /**
     * 对<b>已保存</b>的档案重新探测：用档案里存的 Key 探，并把结果<b>写回档案</b>。
     *
     * <p>管控台卡片上的「测试」走这里 —— 探完卡片上的标签即刷新。</p>
     */
    @PostMapping("/model-profiles/{alias}/probe")
    public Map<String, Object> probeSaved(@PathVariable("alias") String alias) {
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        ModelProbe.Result probed = runProbe(existing.baseUrl(), existing.apiKey(), existing.modelName());

        ModelProfile updated = new ModelProfile(existing.alias(), probed.endpoints(), probed.input(),
                probed.output(), existing.baseUrl(), existing.apiKey(), existing.modelName(),
                existing.temperature(), existing.maxTokens(),
                probed.dimension() != null ? probed.dimension() : existing.dimensions(),
                probed.capabilities(), existing.fallbacks());
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }

        Map<String, Object> out = probeBody(probed);
        out.put("alias", alias);
        out.put("profile", profileView(updated));
        return out;
    }

    private ModelProbe.Result runProbe(String baseUrl, String apiKey, String modelName) {
        try {
            return probe.probe(baseUrl, apiKey, modelName);
        } catch (IllegalStateException e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE, "探测失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE, "探测失败：" + LlmFailureDescriber.describe(e));
        }
    }

    private static Map<String, Object> probeBody(ModelProbe.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("endpoints", result.endpoints());
        out.put("input", result.input());
        out.put("output", result.output());
        out.put("capabilities", result.capabilities());
        out.put("dimension", result.dimension());
        out.put("message", result.note());
        return out;
    }

    /**
     * 删除档案。仍被域绑定时拒绝，并列出是哪些域 —— 否则那些域下次调用会直接失败。
     */
    @DeleteMapping("/model-profiles/{alias}")
    public Map<String, Object> delete(@PathVariable("alias") String alias) {
        ModelProfileRegistry.DeleteResult deleted = registry.delete(alias);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        return result("deleted", alias);
    }

    /**
     * 连通性测试：复用「模型设置」页同一套探测逻辑（只发一条极短请求，避免浪费额度）。
     */
    @PostMapping("/model-profiles/{alias}/test")
    public Map<String, Object> test(@PathVariable("alias") String alias) {
        ModelProfile profile = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        LlmSettings probe = new LlmSettings();
        probe.setChatBaseUrl(profile.baseUrl());
        probe.setChatApiKey(profile.apiKey());
        probe.setChatModelName(profile.modelName());
        probe.setChatTemperature(profile.temperature());

        String reply;
        try {
            reply = holder.testChatConnection(probe);
        } catch (Exception e) {
            // 失败原因用与模型设置页同一套翻译，措辞一致
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE,
                    "连接失败：" + LlmFailureDescriber.describe(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("alias", alias);
        body.put("success", true);
        body.put("reply", reply);
        return body;
    }

    /**
     * 设置域的可调用模型列表（**整体覆盖**，顺序即优先级）；{@code aliases} 空表示解绑，解绑后该域走默认别名。
     */
    @PutMapping("/model-bindings/{domain}")
    public Map<String, Object> bind(@PathVariable("domain") String domain,
                                    @RequestBody(required = false) BindBody body) {
        List<String> aliases = body == null ? null : body.getAliases();
        String failure = registry.bind(domain, aliases);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        boolean unbound = aliases == null || aliases.isEmpty();
        Map<String, Object> result = result(unbound ? "unbound" : "bound", domain);
        result.put("aliases", registry.resolveAliases(domain));
        result.put("domainBindings", registry.domainBindings());
        return result;
    }

    // ==================== 视图 ====================

    private Map<String, Object> profileView(ModelProfile profile) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("alias", profile.alias());
        item.put("endpoints", profile.endpoints());
        item.put("input", profile.input());
        item.put("output", profile.output());
        item.put("baseUrl", profile.baseUrl());
        item.put("modelName", profile.modelName());
        item.put("apiKeyMasked", profile.maskedApiKey());
        item.put("temperature", profile.temperature());
        item.put("maxTokens", profile.maxTokens());
        item.put("dimensions", profile.dimensions());
        item.put("capabilities", profile.capabilities());
        item.put("fallbacks", profile.fallbacks());
        item.put("capabilityHint", profile.capabilityHint());
        item.put("usedByDomains", registry.domainsUsing(profile.alias()));
        return item;
    }

    private Map<String, Object> result(String action, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("action", action);
        body.put("target", target);
        body.put("settingsFile", store.filePath());
        return body;
    }

    /** 新建/覆盖档案的请求体（探测接口复用其中 baseUrl / apiKey / modelName） */
    @Data
    public static class ProfileBody {
        private String alias;
        /** 端点族（可多选；空 = chat） */
        private List<String> endpoints;
        /** 输入模态 */
        private List<String> input;
        /** 输出模态 */
        private List<String> output;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        private List<String> capabilities;
        private List<String> fallbacks;
    }

    /** 绑定请求体（aliases 空 = 解绑；顺序即优先级） */
    @Data
    public static class BindBody {
        private List<String> aliases;
    }
}
