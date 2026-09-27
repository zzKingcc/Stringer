package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.BaseException;
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
 *   <li>一个域<b>只绑一个</b>档案，再次设置即覆盖；</li>
 *   <li>内置别名 {@code default} 不可删、不可被占用 —— 它是未绑定域的落点，
 *       对应管控台「模型设置」页那套配置。</li>
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

    public AdminModelProfileController(ModelProfileRegistry registry,
                                       ModelProfileStore store,
                                       LlmModelHolder holder) {
        this.registry = registry;
        this.store = store;
        this.holder = holder;
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
        body.put("builtinAlias", ModelProfileRegistry.BUILTIN_DEFAULT);
        body.put("defaultAlias", registry.snapshot().getDefaultAlias());
        body.put("builtinChat", builtinChatView());
        body.put("profiles", profiles);
        body.put("domainBindings", registry.domainBindings());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 新建或整体覆盖一个档案（同名即覆盖）。
     *
     * <p>{@code apiKey} 留空表示<b>沿用原值</b> —— 管控台只回填脱敏后的 Key，不应要求用户重填。</p>
     */
    @PostMapping("/model-profiles")
    public Map<String, Object> save(@RequestBody ProfileBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias).orElse(null);
        String apiKey = body.getApiKey() == null || body.getApiKey().isBlank()
                ? (existing == null ? null : existing.apiKey())
                : body.getApiKey();

        ModelProfile profile = new ModelProfile(alias, body.getBaseUrl(), apiKey, body.getModelName(),
                body.getTemperature(), body.getMaxTokens(), body.getCapabilities(), body.getFallbacks());

        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        return result("saved", alias);
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
     * 绑定域到档案（单值覆盖）；{@code alias} 传空表示解绑，解绑后该域走默认别名。
     */
    @PutMapping("/model-bindings/{domain}")
    public Map<String, Object> bind(@PathVariable("domain") String domain,
                                    @RequestBody(required = false) BindBody body) {
        String alias = body == null ? null : body.getAlias();
        String failure = registry.bind(domain, alias);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> result = result(alias == null || alias.isBlank() ? "unbound" : "bound", domain);
        result.put("alias", alias == null ? "" : alias.trim());
        result.put("domainBindings", registry.domainBindings());
        return result;
    }

    // ==================== 视图 ====================

    private Map<String, Object> profileView(ModelProfile profile) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("alias", profile.alias());
        item.put("baseUrl", profile.baseUrl());
        item.put("modelName", profile.modelName());
        item.put("apiKeyMasked", profile.maskedApiKey());
        item.put("temperature", profile.temperature());
        item.put("maxTokens", profile.maxTokens());
        item.put("capabilities", profile.capabilities());
        item.put("fallbacks", profile.fallbacks());
        item.put("capabilityHint", profile.capabilityHint());
        item.put("usedByDomains", registry.domainsUsing(profile.alias()));
        return item;
    }

    /**
     * 内置 default 的当前值（＝管控台「模型设置」页那套，只读展示，改它请去模型设置页）。
     */
    private Map<String, Object> builtinChatView() {
        LlmSettings settings = holder.currentSettings();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("alias", ModelProfileRegistry.BUILTIN_DEFAULT);
        if (settings == null) {
            item.put("configured", false);
            return item;
        }
        item.put("configured", settings.isUsable());
        item.put("baseUrl", settings.getChatBaseUrl());
        item.put("modelName", settings.getChatModelName());
        item.put("apiKeyMasked", settings.getMaskedChatApiKey());
        item.put("temperature", settings.getChatTemperature());
        item.put("maxTokens", settings.getChatMaxTokens());
        item.put("usedByDomains", registry.domainsUsing(ModelProfileRegistry.BUILTIN_DEFAULT));
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

    /** 新建/覆盖档案的请求体 */
    @Data
    public static class ProfileBody {
        private String alias;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        private List<String> capabilities;
        private List<String> fallbacks;
    }

    /** 绑定请求体（alias 留空 = 解绑） */
    @Data
    public static class BindBody {
        private String alias;
    }
}
