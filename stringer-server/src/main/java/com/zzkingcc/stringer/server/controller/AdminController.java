package com.zzkingcc.stringer.server.controller;

import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.tool.ToolDescriptor;
import com.zzkingcc.stringer.infrastructure.elasticsearch.EsIndexManager;
import com.zzkingcc.stringer.runtime.domain.DomainRegistry;
import com.zzkingcc.stringer.runtime.tool.InstanceLifecycle;
import com.zzkingcc.stringer.runtime.tool.InstanceRegistry;
import com.zzkingcc.stringer.runtime.tool.InstanceSession;
import com.zzkingcc.stringer.runtime.tool.InstanceState;
import com.zzkingcc.stringer.runtime.tool.ToolRegistry;
import com.zzkingcc.stringer.runtime.tool.ToolRouter;
import com.zzkingcc.stringer.server.config.RagProperties;
import com.zzkingcc.stringer.server.config.RedisProperties;
import com.zzkingcc.stringer.server.config.RetrievalConfiguration;
import com.zzkingcc.stringer.server.config.PromptProperties;
import com.zzkingcc.stringer.server.prompt.ProfileSystemPromptResolver;
import com.zzkingcc.stringer.server.settings.EsCompatibility;
import com.zzkingcc.stringer.server.settings.InfraSettings;
import com.zzkingcc.stringer.server.settings.InfraSettingsHolder;
import com.zzkingcc.stringer.server.settings.InfraSettingsStore;
import com.zzkingcc.stringer.server.settings.LlmFailureDescriber;
import com.zzkingcc.stringer.server.settings.LlmModelCatalog;
import com.zzkingcc.stringer.server.settings.LlmModelHolder;
import com.zzkingcc.stringer.server.settings.LlmSettings;
import com.zzkingcc.stringer.server.settings.LlmSettingsStore;
import com.zzkingcc.stringer.server.settings.ProfileSettings;
import com.zzkingcc.stringer.server.settings.ProfileSettingsStore;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zzkingcc.stringer.server.knowledge.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Stringer 服务端管控接口。
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 连接类失败对用户的基础措辞；后面会拼上具体原因（如「连接失败：API Key 无效或已过期」） */
    private static final String CONNECT_FAILED = "连接失败";

    private final ToolRouter toolRouter;
    private final RetrievalConfiguration retrievalConfiguration;
    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore embeddingStore;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final LlmSettingsStore settingsStore;
    private final LlmModelCatalog modelCatalog;
    private final InfraSettingsHolder infraHolder;
    private final InfraSettingsStore infraSettingsStore;
    private final RedisProperties redisProperties;
    private final ProfileSettingsStore profileSettingsStore;
    private final PromptProperties promptProperties;
    private final ToolRegistry toolRegistry;
    /** 域注册表：内置域与人工创建的域在这里；工具声明派生的域在 ToolRegistry 里 */
    private final DomainRegistry domainRegistry;
    private final InstanceRegistry instanceRegistry;
    private final InstanceLifecycle instanceLifecycle;
    private final KnowledgeBaseService knowledgeBaseService;

    public AdminController(ToolRouter toolRouter,
                           RetrievalConfiguration retrievalConfiguration,
                           @Qualifier("stringerElasticsearchClient") ElasticsearchClient esClient,
                           @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                           @Qualifier("myEmbeddingStore") EmbeddingStore embeddingStore,
                           RagProperties ragProperties,
                           LlmModelHolder modelHolder,
                           LlmSettingsStore settingsStore,
                           LlmModelCatalog modelCatalog,
                           InfraSettingsHolder infraHolder,
                           InfraSettingsStore infraSettingsStore,
                           RedisProperties redisProperties,
                           ProfileSettingsStore profileSettingsStore,
                           PromptProperties promptProperties,
                           ToolRegistry toolRegistry,
                           InstanceRegistry instanceRegistry,
                           InstanceLifecycle instanceLifecycle,
                           KnowledgeBaseService knowledgeBaseService,
                           DomainRegistry domainRegistry) {
        this.toolRouter = toolRouter;
        this.retrievalConfiguration = retrievalConfiguration;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        this.settingsStore = settingsStore;
        this.modelCatalog = modelCatalog;
        this.infraHolder = infraHolder;
        this.infraSettingsStore = infraSettingsStore;
        this.redisProperties = redisProperties;
        this.profileSettingsStore = profileSettingsStore;
        this.promptProperties = promptProperties;
        this.toolRegistry = toolRegistry;
        this.instanceRegistry = instanceRegistry;
        this.instanceLifecycle = instanceLifecycle;
        this.knowledgeBaseService = knowledgeBaseService;
        this.domainRegistry = domainRegistry;
    }

    /**
     * 读取当前模型设置（API Key 脱敏）
     */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        LlmSettings current = modelHolder.currentSettings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatBaseUrl", current.getChatBaseUrl());
        body.put("chatApiKeyMasked", current.getMaskedChatApiKey());
        body.put("chatApiKeySet", current.getChatApiKey() != null && !current.getChatApiKey().isBlank());
        body.put("chatModelName", current.getChatModelName());
        body.put("chatTemperature", current.getChatTemperature());
        body.put("chatMaxTokens", current.getChatMaxTokens());
        body.put("embeddingBaseUrl", current.getEmbeddingBaseUrl());
        body.put("embeddingApiKeyMasked", current.getMaskedEmbeddingApiKey());
        body.put("embeddingModelName", current.getEmbeddingModelName());
        body.put("embeddingDimensions", current.getEmbeddingDimensions());
        body.put("chatConfigured", modelHolder.isConfigured());
        body.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        body.put("settingsFile", settingsStore.filePath());
        return body;
    }

    /**
     * 保存模型设置并立即生效（无需重启）
     *
     * @param rebuildIndex 管控台已二次确认"维度变化、同意重建索引"时为 {@code true}
     */
    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody LlmSettings settings,
                                            @RequestParam(defaultValue = "false") boolean rebuildIndex) {
        log.info("[管控] 收到模型设置保存请求: chatBaseUrl={}, chatModel={}, embeddingModel={}, rebuildIndex={}",
                settings.getChatBaseUrl(), settings.getChatModelName(),
                settings.getEmbeddingModelName(), rebuildIndex);
        // Key 留空表示"不修改"，用已保存的值补齐——避免管控台不发明文导致 Key 被清空
        fillBlankKeysFromCurrent(settings);

        DimCheck dim = checkEmbeddingDimension(settings);
        if (dim.verdict() == DimVerdict.DECLARED_MISMATCH) {
            // 声明了一个模型不支持的维度：保存下去必然灌库失败，直接拒绝（不受重建开关影响）
            Map<String, Object> fail = failBody(dim.message());
            fail.put("declaredDimension", dim.declaredDims());
            fail.put("newDimension", dim.newDims());
            return fail;
        }
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD && !rebuildIndex) {
            // 维度变了但用户还没确认重建 → 本次保存不生效，配置保持原样
            Map<String, Object> fail = failBody(dim.message());
            fail.put("requiresRebuild", true);
            fail.put("indexDimension", dim.indexDims());
            fail.put("newDimension", dim.newDims());
            return fail;
        }

        try {
            modelHolder.apply(settings);
        } catch (IllegalStateException e) {
            return failBody(e.getMessage());
        }

        // 维度变化且用户已确认 → 紧接着重建索引。
        // 注意注入的 embeddingModel 是持有者的委派代理，apply() 之后已指向新模型。
        boolean rebuilt = false;
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD) {
            log.warn("[管控] 维度 {} → {}，开始重建知识库索引（index={}）",
                    dim.indexDims(), dim.newDims(), ragProperties.getIndexName());
            try {
                // 维度必须显式传入：索引维度是 mapping 的不可变参数，取"三处同源"的公共取值点。
                // apply() 已生效，这里拿到的就是新模型的维度。
                // forceDelete=true：维度变了，旧索引留着也没用——不删就永远修不好。
                retrievalConfiguration.rebuildKnowledgeIndex(esClient, embeddingModel, embeddingStore,
                        ragProperties, modelHolder.effectiveEmbeddingDimension(), true);
                rebuilt = true;
                log.info("[管控] 知识库索引重建完成（dims={}）", dim.newDims());
            } catch (Exception e) {
                // 配置已落盘并生效，只是索引重建失败 —— 必须如实告知，不能报成功
                log.error("[管控] 知识库索引重建失败（配置已生效）: {}", e.getMessage(), e);
                Map<String, Object> fail = failBody(
                        "配置已保存并生效，但知识库索引重建失败：" + e.getMessage()
                                + "。请到「知识库」页重试「触发重建」，重建成功前灌库与检索不可用。");
                fail.put("saved", true);
                fail.put("rebuilt", false);
                fillKeyState(fail);
                return fail;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chatConfigured", modelHolder.isConfigured());
        result.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        fillKeyState(result);
        result.put("rebuilt", rebuilt);
        // message 不带主语，由管控台拼成「<模型名>：<message>」
        result.put("message", rebuilt
                ? "已保存并立即生效，知识库索引已按新维度 " + dim.newDims() + " 重建"
                : (dim.note() != null ? "已保存并立即生效（" + dim.note() + "）" : "已保存并立即生效"));
        return result;
    }

    /** 保存失败/需确认时的统一响应体 */
    private static Map<String, Object> failBody(String message) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("success", false);
        fail.put("error", message);
        return fail;
    }

    /**
     * 回传 Key 掩码/配置状态。
     */
    private void fillKeyState(Map<String, Object> body) {
        LlmSettings now = modelHolder.currentSettings();
        body.put("chatApiKeyMasked", now.getMaskedChatApiKey());
        body.put("chatApiKeySet", hasText(now.getChatApiKey()));
        body.put("embeddingApiKeyMasked", now.getMaskedEmbeddingApiKey());
    }

    // ===== 向量维度预检 =====

    /** 维度预检结论 */
    private enum DimVerdict {
        /** 无变化、或索引维度与实测一致，或尚无索引 */
        OK,
        /** 维度与现有索引不符，必须重建索引后才能保存 */
        NEEDS_REBUILD,
        /** 声明的维度与实测不符（该模型不支持这个维度） */
        DECLARED_MISMATCH,
        /** 连不上/填不全，无法实测；放行保存并提示 */
        UNKNOWN
    }

    /**
     * @param verdict    结论
     * @param indexDims  现有索引维度（无索引为 null）
     * @param newDims    实测的新维度（未实测为 null）
     * @param declaredDims 用户声明的维度（未声明为 null）
     * @param message    给用户看的说明（OK/UNKNOWN 时为附加提示，可为 null）
     */
    private record DimCheck(DimVerdict verdict, Integer indexDims, Integer newDims,
                            Integer declaredDims, String message) {
        /** 附加到成功消息后的短提示 */
        String note() {
            return verdict == DimVerdict.UNKNOWN ? message : null;
        }
    }

    /**
     * 保存前的向量维度预检。
     */
    private DimCheck checkEmbeddingDimension(LlmSettings incoming) {
        Integer indexDims = null;
        if (!embeddingChanged(incoming, modelHolder.currentSettings())) {
            return new DimCheck(DimVerdict.OK, null, null, null, null);
        }
        // 读索引维度失败（ES 未配置 / 不可达 / 索引尚不存在）不阻断保存：拿不到就对不了账，
        // 退化为"不做预检"即可。与"实测维度失败必须放行"同一哲学——否则用户会被卡在
        // "必须先配好 ES 才能改模型配置"的循环里，自救路径被切断。
        try {
            indexDims = EsIndexManager.currentVectorDims(esClient, ragProperties.getIndexName());
        } catch (Exception e) {
            log.warn("[管控] 读取索引向量维度失败，跳过维度预检（ES 未配置或索引不存在）: {}",
                    e.getMessage());
        }

        LlmModelHolder.EmbeddingProbe probe;
        try {
            probe = modelHolder.testEmbeddingConnection(incoming);
        } catch (Exception e) {
            // 未填全 / Key 无效 / 网络不通 —— 此时必须放行保存：
            // 若因"测不出维度"就禁止保存，用户连改 Key 自救都做不到。
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 保存前无法实测向量维度，跳过预检并放行: {}", reason);
            return new DimCheck(DimVerdict.UNKNOWN, indexDims, null, null,
                    "未能实测向量维度（" + reason + "），已按原样保存；若索引维度与模型不符，灌库前请先重建索引");
        }

        int newDims = probe.actualDimension();
        if (probe.mismatched()) {
            return new DimCheck(DimVerdict.DECLARED_MISMATCH, indexDims, newDims, probe.declaredDimension(),
                    "向量维度声明与实测不符：声明 " + probe.declaredDimension() + " 维，实际返回 " + newDims
                            + " 维。上游对不支持的维度是静默忽略（不报错），所以这个声明不会被兑现，"
                            + "按它建索引会导致灌库被 ES 拒绝。请改填该模型真正支持的维度。");
        }
        if (indexDims == null) {
            return new DimCheck(DimVerdict.OK, null, newDims, probe.declaredDimension(), null);
        }
        if (indexDims == newDims) {
            return new DimCheck(DimVerdict.OK, indexDims, newDims, probe.declaredDimension(), null);
        }
        return new DimCheck(DimVerdict.NEEDS_REBUILD, indexDims, newDims, probe.declaredDimension(),
                "向量维度将由 " + indexDims + " 变为 " + newDims + "，与已建好的知识库索引不一致。"
                        + "ES 索引的向量维度是 mapping 参数，建好后无法修改，必须先重建索引；"
                        + "否则写入会被 ES 以维度不符拒绝，检索同样报错，且不会自愈。");
    }

    /**
     * 本次请求是否改动了向量配置。
     */
    private static boolean embeddingChanged(LlmSettings in, LlmSettings cur) {
        if (cur == null) {
            return true;
        }
        return !Objects.equals(in.getEmbeddingBaseUrl(), cur.getEmbeddingBaseUrl())
                || !Objects.equals(in.getEmbeddingApiKey(), cur.getEmbeddingApiKey())
                || !Objects.equals(in.getEmbeddingModelName(), cur.getEmbeddingModelName())
                || !Objects.equals(in.getEmbeddingDimensions(), cur.getEmbeddingDimensions());
    }

    /**
     * 连通性测试（保存前先验，避免填错后才发现）
     *
     * @param type {@code chat}（默认）或 {@code embedding}
     */
    @PostMapping("/settings/test")
    public Map<String, Object> testSettings(@RequestBody(required = false) LlmSettings settings,
                                            @RequestParam(defaultValue = "chat") String type) {
        LlmSettings candidate = settings == null ? new LlmSettings() : settings;
        fillBlankKeysFromCurrent(candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("embedding".equalsIgnoreCase(type)) {
                testEmbedding(candidate, result);
            } else {
                String reply = modelHolder.testChatConnection(candidate);
                result.put("success", true);
                result.put("type", "chat");
                result.put("reply", reply);
                result.put("message", "对话模型连通正常，模型回复：" + reply);
            }
        } catch (Exception e) {
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 模型连通性测试失败: 原因={} 原始={}", reason, e.getMessage());
            result.put("success", false);
            result.put("type", type);
            result.put("message", CONNECT_FAILED + "：" + reason);
            result.put("error", reason);
            result.put("detail", e.getMessage());
        }
        return result;
    }

    /**
     * 向量测试的完整裁决：连通性 + 维度契约 + 索引一致性
     */
    private void testEmbedding(LlmSettings candidate, Map<String, Object> result) {
        LlmModelHolder.EmbeddingProbe probe = modelHolder.testEmbeddingConnection(candidate);
        int actual = probe.actualDimension();
        Integer declared = probe.declaredDimension();
        Integer indexDims = EsIndexManager.currentVectorDims(esClient, ragProperties.getIndexName());

        result.put("type", "embedding");
        result.put("dimension", actual);
        result.put("declaredDimension", declared);
        result.put("indexDimension", indexDims);

        if (probe.mismatched()) {
            String detail = "该模型不支持自定义维度：声明 " + declared + " 维，实际返回 " + actual + " 维";
            log.warn("[管控] 向量维度校验未通过：{}", detail);
            result.put("success", false);
            result.put("message", "已连通，但" + detail + "。请填写该模型真正支持的维度，"
                    + "或改用支持自定义维度的模型（具体支持范围请查服务商文档）。");
            result.put("error", detail);
            return;
        }

        String dimNote = declared != null
                ? "维度 " + actual + " 维已确认（与声明一致）"
                : "该模型默认维度 " + actual + " 维（未指定维度，ES 索引将按实测值创建）";
        String idxNote;
        if (indexDims == null) {
            idxNote = "；当前尚无知识库索引，下次启动或手动重建时按该维度创建";
        } else if (indexDims == actual) {
            idxNote = "；当前索引维度 " + indexDims + "，一致，无需重建";
        } else {
            idxNote = "；当前索引维度 " + indexDims + " 与模型不一致，需到「知识库」页重建索引后才能灌库";
        }
        result.put("success", true);
        result.put("message", "向量模型连通正常，" + dimNote + idxNote);
    }

    /**
     * 拉取服务商侧的模型目录，供管控台「下拉选择 + 手动输入」控件使用
     * @param type {@code chat}（默认）或 {@code embedding}
     */
    @PostMapping("/models")
    public Map<String, Object> models(@RequestBody(required = false) LlmSettings settings,
                                      @RequestParam(defaultValue = "chat") String type) {
        boolean embedding = "embedding".equalsIgnoreCase(type);
        String label = embedding ? "向量模型" : "对话模型";
        LlmSettings candidate = settings == null ? new LlmSettings() : settings;

        String[] endpoint = resolveCatalogEndpoint(candidate, embedding);
        String baseUrl = endpoint[0];
        String apiKey = endpoint[1];

        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasText(baseUrl) || !hasText(apiKey)) {
            String missing = hasText(baseUrl) ? "API Key" : "服务商地址";
            log.warn("[管控] 拉取{}目录未发起：缺少{}", label, missing);
            return connectFailed(result, "缺少" + missing, null);
        }

        try {
            List<String> ids = modelCatalog.listModelIds(baseUrl, apiKey);
            log.info("[管控] 拉取{}目录成功：baseUrl={} 共 {} 个（仅候选池，不代表已授权）",
                    label, baseUrl, ids.size());
            result.put("success", true);
            result.put("models", ids);
            return result;
        } catch (Exception e) {
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 拉取{}目录失败：baseUrl={} 原因={} 原始={}", label, baseUrl, reason, e.getMessage());
            return connectFailed(result, reason, e.getMessage());
        }
    }

    /**
     * 连接失败的统一响应体。
     *
     * @param reason 给用户看的中文原因（如「API Key 无效或已过期」）
     * @param detail 原始报文，仅供接口层诊断与 F12 查看，页面不展示
     */
    private static Map<String, Object> connectFailed(Map<String, Object> result, String reason, String detail) {
        result.put("success", false);
        result.put("message", CONNECT_FAILED + "：" + reason);
        result.put("error", reason);
        result.put("detail", detail);
        return result;
    }

    /** 解析拉取目录要用的地址与 Key：向量缺项回落对话，Key 缺项回落已保存值 */
    private String[] resolveCatalogEndpoint(LlmSettings candidate, boolean embedding) {
        LlmSettings current = modelHolder.currentSettings();
        if (embedding) {
            String baseUrl = hasText(candidate.getEmbeddingBaseUrl())
                    ? candidate.getEmbeddingBaseUrl() : candidate.getChatBaseUrl();
            String apiKey = firstText(candidate.getEmbeddingApiKey(), candidate.getChatApiKey(),
                    current == null ? null : current.effectiveEmbeddingApiKey());
            return new String[]{baseUrl, apiKey};
        }
        String apiKey = firstText(candidate.getChatApiKey(),
                current == null ? null : current.getChatApiKey());
        return new String[]{candidate.getChatBaseUrl(), apiKey};
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Key 留空 = "不修改"，用已保存值补齐；这样用户只改地址/模型名时不必重填 Key */
    private void fillBlankKeysFromCurrent(LlmSettings incoming) {
        LlmSettings current = modelHolder.currentSettings();
        if (current == null) {
            return;
        }
        if (incoming.getChatApiKey() == null || incoming.getChatApiKey().isBlank()) {
            incoming.setChatApiKey(current.getChatApiKey());
        }
        if (incoming.getEmbeddingApiKey() == null || incoming.getEmbeddingApiKey().isBlank()) {
            incoming.setEmbeddingApiKey(current.getEmbeddingApiKey());
        }
    }

    /**
     * 已注册工具清单
     */
    @GetMapping("/tools")
    public List<ToolDescriptor> tools() {
        return toolRouter.getToolDescriptors();
    }

    /**
     * 「域空间」：注册表里每个域实际注册到的内容 + 全局统计。
     */
    @GetMapping("/domains")
    public Map<String, Object> domains() {
        List<ToolDescriptor> all = toolRouter.getToolDescriptors();
        Set<String> known = new TreeSet<>(toolRouter.getKnownProfiles());
        ProfileSettings console = profileSettingsStore.load();
        ProfileSystemPromptResolver resolver =
                new ProfileSystemPromptResolver(profileSettingsStore, promptProperties);

        /* 全域可见＝显式声明了通配 "*" 的工具（它们在每个域里都会出现）。
           注意与「未声明域」区分：未声明＝只属于兜底域 default，不再等于全域可见。 */
        List<ToolDescriptor> globalTools = all.stream()
                .filter(d -> d.profiles() != null && d.profiles().contains(Domains.ANY))
                .toList();

        int approvalTotal = 0;
        int missingPrompt = 0;
        List<Map<String, Object>> domains = new ArrayList<>();
        for (String domain : known) {
            Set<String> visibleNames = toolRouter.getToolSpecifications(domain).stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toSet());
            List<ToolDescriptor> visible = all.stream()
                    .filter(d -> visibleNames.contains(d.name()))
                    .sorted(Comparator.comparing(ToolDescriptor::name))
                    .toList();

            List<Map<String, Object>> tools = new ArrayList<>();
            Map<String, Integer> bySideEffect = new LinkedHashMap<>();
            bySideEffect.put("READ", 0);
            bySideEffect.put("WRITE", 0);
            bySideEffect.put("DESTRUCTIVE", 0);
            int approval = 0;
            for (ToolDescriptor d : visible) {
                tools.add(toolView(d));

                if (d.sideEffect() != null) {
                    bySideEffect.merge(d.sideEffect().name(), 1, Integer::sum);
                }
                if (d.requiresApproval()) {
                    approval++;
                }
            }

            String diff = firstNonBlank(console.profilePrompt(domain),
                    promptProperties.profilePrompt(domain));
            boolean hasPrompt = diff != null && !diff.isBlank();
            if (!hasPrompt) {
                missingPrompt++;
            }
            approvalTotal += approval;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", domain);
            // 来源决定这个域能不能删：内置是兜底、派生归工具，只有人工创建的域可删
            String source = domainSource(domain);
            d.put("source", source);
            d.put("sourceLabel", sourceLabel(source));
            d.put("deletable", DomainRegistry.Source.MANUAL.name().equals(source));
            d.put("toolCount", tools.size());
            d.put("exclusiveToolCount", tools.stream()
                    .filter(t -> Boolean.TRUE.equals(t.get("exclusive"))).count());
            d.put("providerCount", tools.stream()
                    .map(t -> String.valueOf(t.get("provider")))
                    .distinct()
                    .count());
            d.put("approvalCount", approval);
            d.put("sideEffects", bySideEffect);
            d.put("hasPrompt", hasPrompt);
            d.put("promptLength", hasPrompt ? diff.trim().length() : 0);
            // 该域实际生效的 SystemMessage 预览（preview 不打日志，见 ProfileSystemPromptResolver）
            d.put("promptPreview", resolver.preview(domain, console));
            d.put("tools", tools);
            domains.add(d);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("domainCount", known.size());
        stats.put("builtinCount", known.stream().filter(domainRegistry::isBuiltin).count());
        stats.put("manualCount", known.stream()
                .filter(domain -> DomainRegistry.Source.MANUAL.name().equals(domainSource(domain))).count());
        stats.put("derivedCount", known.stream()
                .filter(domain -> "DERIVED".equals(domainSource(domain))).count());
        stats.put("toolCount", all.size());
        stats.put("globalToolCount", globalTools.size());
        stats.put("approvalToolCount", approvalTotal);
        stats.put("missingPromptCount", missingPrompt);

        List<Map<String, Object>> globalList = new ArrayList<>();
        for (ToolDescriptor d : globalTools) {
            globalList.add(toolView(d));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", stats);
        body.put("domains", domains);
        body.put("globalTools", globalList);
        List<String> orphans = new ArrayList<>(console.getProfiles().keySet());
        orphans.removeAll(known);
        Collections.sort(orphans);
        body.put("orphanPrompts", orphans);
        body.put("settingsFile", profileSettingsStore.filePath());
        return body;
    }

    /**
     * 域来源：内置（兜底域）/ 人工创建 / 工具声明派生。
     *
     * <p>两处来源合并判断：{@code DomainRegistry} 管内置与人工，派生域归 {@code ToolRegistry}。</p>
     */
    private String domainSource(String domain) {
        if (domainRegistry.isBuiltin(domain)) {
            return DomainRegistry.Source.BUILTIN.name();
        }
        if (domainRegistry.contains(domain)) {
            return DomainRegistry.Source.MANUAL.name();
        }
        return "DERIVED";
    }

    /** 来源的中文标签（给管控台直接用，避免前端各写一份映射） */
    private static String sourceLabel(String source) {
        return switch (source) {
            case "BUILTIN" -> "内置";
            case "MANUAL" -> "人工创建";
            default -> "工具派生";
        };
    }

    /**
     * 「在线实例」：谁在提供工具、它还活着吗。
     */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        List<InstanceSession> sessions = instanceRegistry.all().stream()
                .sorted(Comparator.comparing(InstanceSession::instanceId))
                .toList();

        int online = 0;
        int draining = 0;
        int muted = 0;
        int forceOffline = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (InstanceSession session : sessions) {
            if (session.state() == InstanceState.ONLINE) {
                online++;
            } else if (session.state() == InstanceState.MUTED) {
                muted++;
            } else if (session.state() == InstanceState.DRAINING) {
                draining++;
            } else if (session.state().isRejecting()) {
                forceOffline++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", session.instanceId());
            item.put("endpoint", session.endpoint());
            item.put("state", session.state().name());
            item.put("stateLabel", session.state().label());
            item.put("rejecting", session.state().isRejecting());
            item.put("muted", session.state() == InstanceState.MUTED);
            item.put("lastSeen", session.lastSeen());
            item.put("silentMillis", session.silentMillis(System.currentTimeMillis()));
            item.put("toolCount", session.toolNames().size());
            item.put("toolNames", List.copyOf(new TreeSet<>(session.toolNames())));
            // 摘要只给前 12 位：它的用途是"排查时确认两端看到的是不是同一份 manifest"，
            // 不需要完整值（完整值 64 位只会把表格撑开）
            String digest = session.manifestDigest();
            item.put("digest", digest == null ? "" : digest.substring(0, Math.min(12, digest.length())));
            list.add(item);
        }

        // 副本总数：注册表里所有非本地副本之和（与"逻辑工具数"是两个量，都要看得见）
        int replicas = toolRegistry.all().stream()
                .mapToInt(r -> (int) r.endpoints().stream().filter(e -> !e.isLocal()).count())
                .sum();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("instanceCount", sessions.size());
        stats.put("onlineCount", online);
        stats.put("mutedCount", muted);
        stats.put("drainingCount", draining);
        stats.put("forceOfflineCount", forceOffline);
        stats.put("registeredToolCount", toolRegistry.size());
        stats.put("remoteReplicaCount", replicas);
        stats.put("timeoutSeconds", instanceLifecycle.timeoutMillis() / 1000);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", stats);
        body.put("instances", list);
        return body;
    }

    /**
     * 熔断一个工具实例：不断心跳，只摘副本。
     */
    @PostMapping("/instances/{instanceId}/mute")
    public Map<String, Object> muteInstance(@PathVariable String instanceId) {
        int tools = toolRegistry.toolsOfInstance(instanceId).size();
        boolean muted = instanceLifecycle.mute(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", muted);
        body.put("instanceId", instanceId);
        body.put("removedToolCount", muted ? tools : 0);
        body.put("message", muted
                ? "已熔断：摘除 " + tools + " 个工具条目的副本（心跳仍受理，可在控制台「恢复」）"
                : "该实例不在在线表中，或已被强制下线");
        return body;
    }

    /**
     * 解除熔断：副本在下一次心跳到来时重建。
     */
    @PostMapping("/instances/{instanceId}/restore")
    public Map<String, Object> restoreInstance(@PathVariable String instanceId) {
        boolean restored = instanceLifecycle.restore(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", restored);
        body.put("instanceId", instanceId);
        body.put("message", restored
                ? "已解除熔断：下一次心跳会重建副本（会话不受影响）"
                : "该实例不在在线表中，或当前不是熔断状态");
        return body;
    }

    /**
     * 强制下线一个工具实例。
     */
    @PostMapping("/instances/{instanceId}/offline")
    public Map<String, Object> forceOfflineInstance(@PathVariable String instanceId) {
        int tools = toolRegistry.toolsOfInstance(instanceId).size();
        boolean kicked = instanceLifecycle.forceOffline(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", kicked);
        body.put("instanceId", instanceId);
        body.put("removedToolCount", kicked ? tools : 0);
        body.put("message", kicked
                ? "已强制下线：摘除 " + tools + " 个工具条目的副本，其下次心跳将收到 410（会话不受影响）"
                : "该实例不在在线表中（可能已自然下线）");
        return body;
    }

    /**
     * 「域空间」里一个工具的统一呈现字段。
     */
    private Map<String, Object> toolView(ToolDescriptor d) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", d.name());
        t.put("description", d.description());
        t.put("category", d.category());
        t.put("version", d.version());
        t.put("sideEffect", d.sideEffect() == null ? null : d.sideEffect().name());
        t.put("idempotent", d.idempotent());
        t.put("toModel", d.toModel());
        t.put("requiresApproval", d.requiresApproval());
        t.put("approvalMode", d.approval() == null ? "NONE" : d.approval().mode());
        t.put("approvalReason", d.approval() == null ? "" : d.approval().reason());
        t.put("paramCount", d.params() == null ? 0 : d.params().size());
        t.put("provider", providerOf(d.source()));
        t.put("source", d.source());
        // 该工具是否"只声明了一个域"——一眼看出域的专属能力，还是全域通用能力
        t.put("exclusive", d.profiles() != null && d.profiles().size() == 1);
        return t;
    }

    // ===== 知识库文档（列表 / 上传 / 删除 / 状态） =====

    /**
     * 已入库文档列表
     */
    @GetMapping("/kb/documents")
    public Map<String, Object> kbDocuments() {
        List<KnowledgeBaseService.DocumentItem> items = knowledgeBaseService.list();
        List<Map<String, Object>> documents = new ArrayList<>(items.size());
        for (KnowledgeBaseService.DocumentItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", item.docId());
            row.put("fileName", item.fileName());
            row.put("chunks", item.chunks());
            documents.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("count", documents.size());
        body.put("documents", documents);
        return body;
    }

    /**
     * 上传一个知识库文档（multipart，字段名 {@code file}）
     *
     * @param replace {@code true} = 已存在同名文档时先删旧再写入；{@code false} = 同名直接拒绝（60005）
     */
    @PostMapping(value = "/kb/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> kbUpload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "replace", defaultValue = "false") boolean replace)
            throws IOException {
        KnowledgeBaseService.UploadResult result =
                knowledgeBaseService.upload(file.getBytes(), file.getOriginalFilename(), replace);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("success", true);
        body.put("docId", result.docId());
        body.put("fileName", result.fileName());
        body.put("size", result.size());
        body.put("chunks", result.chunks());
        return body;
    }

    /**
     * 删除一个文档的全部切片，并释放其文件名（删除后同名可以再次上传）
     */
    @DeleteMapping("/kb/documents/{docId}")
    public Map<String, Object> kbDelete(@PathVariable String docId) {
        boolean deleted = knowledgeBaseService.delete(docId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("success", true);
        body.put("docId", docId);
        body.put("deleted", deleted);
        return body;
    }

    /**
     * 知识库索引状态：索引是否存在、文档数、切片总数。
     */
    @GetMapping("/kb/status")
    public Map<String, Object> kbStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.putAll(knowledgeBaseService.status());
        return body;
    }

    /**
     * 手动重建知识库索引（诊断 + 删旧 + 建 IK mapping + 校验）
     */
    @PostMapping("/kb/rebuild")
    public Map<String, Object> rebuildKnowledgeIndex() {
        log.info("[管控] 收到知识库重建请求 index={}", ragProperties.getIndexName());
        try {
            int dimensions = modelHolder.effectiveEmbeddingDimension();
            log.info("[管控] 重建索引使用的向量维度 = {}", dimensions);
            // forceDelete=true：手动重建的语义就是"删掉重建"。不删的话，改过的文档会新旧片段共存、
            // 旧片段继续被检索命中，而且维度改错的索引永远修不好。
            retrievalConfiguration.rebuildKnowledgeIndex(
                    esClient, embeddingModel, embeddingStore, ragProperties, dimensions, true);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 0);
            result.put("success", true);
            result.put("index", ragProperties.getIndexName());
            result.put("dimensions", dimensions);
            result.put("message", "已删除旧索引并按维度 " + dimensions + " 重建；索引已清空，请重新上传知识库文档");
            return result;
        } catch (Exception e) {
            log.error("[管控] 知识库重建失败: {}", e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    // ===== 存储配置（ES / Redis） =====
    //
    // 连接信息从 yaml 搬到管控台：先在页面上填 → 「测试连接」验证 → 「保存并生效」热替换。
    // 保存的是"界面那一份"，yaml 留空回落；两者都空即"未配置"（服务照常启动，只跳过灌库）。

    /**
     * 读取 ES / Redis 存储配置（口令脱敏）
     */
    @GetMapping("/infra")
    public Map<String, Object> infra() {
        InfraSettings cur = infraHolder.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("es", esBody(cur.getEs()));
        body.put("redis", redisBody(cur.getRedis()));
        body.put("esConfigured", infraHolder.isEsConfigured());
        body.put("redisConfigured", infraHolder.isRedisConfigured());
        body.put("esSource", infraHolder.esSource());
        body.put("redisSource", infraHolder.redisSource());
        body.put("indexName", ragProperties.getIndexName());
        body.put("settingsFile", infraSettingsStore.filePath());
        return body;
    }

    private static Map<String, Object> esBody(InfraSettings.Es es) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", es.getHost());
        m.put("port", es.getPort());
        m.put("scheme", es.getScheme());
        m.put("username", es.getUsername());
        m.put("passwordMasked", es.getMaskedPassword());
        m.put("passwordSet", hasText(es.getPassword()));
        m.put("connectTimeout", es.getConnectTimeout());
        m.put("socketTimeout", es.getSocketTimeout());
        return m;
    }

    private static Map<String, Object> redisBody(InfraSettings.Redis redis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", redis.getHost());
        m.put("port", redis.getPort());
        m.put("passwordMasked", redis.getMaskedPassword());
        m.put("passwordSet", hasText(redis.getPassword()));
        m.put("database", redis.getDatabase());
        return m;
    }

    /**
     * 保存存储配置并立即生效（无需重启）
     */
    @PostMapping("/infra")
    public Map<String, Object> saveInfra(@RequestBody InfraSettings incoming) {
        log.info("[管控] 收到存储配置保存请求: es={}, redis={}",
                incoming.getEs() == null ? "-" : incoming.getEs().describe(),
                incoming.getRedis() == null ? "-" : incoming.getRedis().describe());
        try {
            infraHolder.apply(incoming);
        } catch (IllegalStateException e) {
            return failBody(e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("esConfigured", infraHolder.isEsConfigured());
        result.put("redisConfigured", infraHolder.isRedisConfigured());
        result.put("esSource", infraHolder.esSource());
        result.put("redisSource", infraHolder.redisSource());
        result.put("message", "存储配置已保存并立即生效");
        fillSecretState(result);

        IndexProbe probe = probeIndex();
        if (probe != null) {
            result.put("indexName", probe.name());
            if (probe.error() == null) {
                result.put("indexExists", probe.exists());
                result.put("indexDocCount", probe.docCount());
                result.put("requiresRebuild",
                        !probe.exists() || (probe.docCount() != null && probe.docCount() == 0));
            } else {
                result.put("indexProbeError", probe.error());
            }
        }
        return result;
    }

    /** 索引探测结果；{@code error} 非空表示"没探成"，与"索引不存在"是两回事 */
    private record IndexProbe(String name, boolean exists, Long docCount, String error) {
    }

    /**
     * 回传口令掩码/是否已设置。
     */
    private void fillSecretState(Map<String, Object> body) {
        InfraSettings cur = infraHolder.current();
        body.put("esPasswordMasked", cur.getEs().getMaskedPassword());
        body.put("esPasswordSet", hasText(cur.getEs().getPassword()));
        body.put("redisPasswordMasked", cur.getRedis().getMaskedPassword());
        body.put("redisPasswordSet", hasText(cur.getRedis().getPassword()));
    }

    private IndexProbe probeIndex() {
        if (!infraHolder.isEsConfigured()) {
            return null;
        }
        String indexName = ragProperties.getIndexName();
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            Long count = exists ? esClient.count(c -> c.index(indexName)).count() : null;
            return new IndexProbe(indexName, exists, count, null);
        } catch (Exception e) {
            log.warn("[管控] 保存后探测索引失败（配置已生效，不影响保存结论）: {}", e.getMessage());
            return new IndexProbe(indexName, false, null, e.getMessage());
        }
    }

    /**
     * 连接测试（保存前先验，避免填错后才发现）
     * @param type {@code es}（默认）或 {@code redis}
     */
    @PostMapping("/infra/test")
    public Map<String, Object> testInfra(@RequestBody(required = false) InfraSettings settings,
                                         @RequestParam(defaultValue = "es") String type) {
        InfraSettings candidate = settings == null ? new InfraSettings() : settings;
        infraHolder.fillBlankSecrets(candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.toLowerCase());
        try {
            if ("redis".equalsIgnoreCase(type)) {
                InfraSettingsHolder.RedisProbe probe =
                        infraHolder.testRedis(candidate.getRedis(), redisProperties);
                result.put("success", true);
                result.put("version", probe.version());
                result.put("mode", probe.mode());
                result.put("database", probe.database());
                result.put("databases", probe.databases());
                result.put("dbSize", probe.dbSize());
                result.put("note", probe.note());
                result.put("message", "Redis 连接正常：版本 " + text(probe.version())
                        + "（" + modeLabel(probe.mode()) + "），库 " + probe.database()
                        + " 当前有 " + text(probe.dbSize()) + " 个 key");
            } else {
                InfraSettingsHolder.EsProbe probe =
                        infraHolder.testEs(candidate.getEs(), ragProperties.getIndexName());
                result.put("success", true);
                result.put("clusterName", probe.clusterName());
                result.put("version", probe.version());
                result.put("buildFlavor", probe.buildFlavor());
                result.put("compatibility", compatBody(probe.compatibility()));
                Map<String, Object> ik = new LinkedHashMap<>();
                ik.put("available", probe.ikAvailable());
                ik.put("note", probe.ikNote());
                result.put("ik", ik);
                result.put("indexName", probe.indexName());
                result.put("indexExists", probe.indexExists());
                result.put("indexDocCount", probe.docCount());
                result.put("message", "Elasticsearch 连接正常：集群 " + text(probe.clusterName())
                        + "（版本 " + text(probe.version()) + "）；" + indexNote(probe));
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "未知原因" : e.getMessage();
            log.warn("[管控] {} 连通性测试失败: {}", type, reason);
            result.put("success", false);
            result.put("message", CONNECT_FAILED + "：" + reason);
            result.put("error", reason);
        }
        return result;
    }

    /** 兼容性判读的传输形态（前端只认 tone 上色 + label 展示 + note/hint 展开） */
    private static Map<String, Object> compatBody(EsCompatibility.Verdict verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", verdict.level().name());
        m.put("tone", verdict.tone());
        m.put("label", verdict.label());
        m.put("note", verdict.note());
        m.put("hint", verdict.hint());
        return m;
    }

    /** Redis 拓扑翻成中文；读不到就如实说读不到 */
    private static String modeLabel(String mode) {
        if (mode == null || mode.isBlank()) {
            return "拓扑未知";
        }
        return switch (mode.trim().toLowerCase()) {
            case "standalone" -> "单机";
            case "cluster" -> "集群";
            case "sentinel" -> "哨兵";
            default -> mode;
        };
    }

    /** 索引现状的一句话说明（测试与保存两条路径共用同一套措辞） */
    private static String indexNote(InfraSettingsHolder.EsProbe probe) {
        if (!probe.indexExists()) {
            return "索引 " + probe.indexName() + " 尚不存在，保存后需到「知识库」页触发一次重建";
        }
        if (probe.docCount() != null && probe.docCount() == 0) {
            return "索引 " + probe.indexName() + " 存在但文档数为 0，需到「知识库」页重建";
        }
        return "索引 " + probe.indexName() + " 已有 " + text(probe.docCount()) + " 个文档";
    }

    // ===== 提示词设定 =====
    //
    // 提示词 = 公共基线 + 域差异，落盘 config/profiles.json（控制台 > yaml）。
    // 域的【存在性】有两个来源：DomainRegistry（内置 default 与人工创建的域）与工具声明的派生域
    // （ToolRegistry）；本页只读不改域本身，增删域在「域空间」。

    /**
     * 读取提示词设定（公共基线 + 各域差异）。
     */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        ProfileSettings console = profileSettingsStore.load();
        Map<String, Object> body = new LinkedHashMap<>();

        // 编辑框里填"生效值"：控制台没写的用 yaml 兜底，避免用户以为空就是没配
        body.put("base", firstNonBlank(console.getBase(), promptProperties.getBase()));
        body.put("profiles", effectiveProfileMap(console));

        Set<String> known = new TreeSet<>(toolRouter.getKnownProfiles());
        List<Map<String, Object>> domains = new ArrayList<>();
        ProfileSystemPromptResolver resolver =
                new ProfileSystemPromptResolver(profileSettingsStore, promptProperties);
        for (String domain : known) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", domain);
            // 工具清单带 source（哪个 Bean#方法提供的）——"域从哪来"的答案就在这儿
            List<Map<String, Object>> tools = new ArrayList<>();
            toolRouter.getToolSpecifications(domain).stream()
                    .map(ToolSpecification::name)
                    .sorted()
                    .forEach(name -> {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", name);
                        t.put("source", toolRouter.getToolDescriptors().stream()
                                .filter(desc -> desc.name().equals(name))
                                .map(ToolDescriptor::source)
                                .findFirst().orElse("-"));
                        tools.add(t);
                    });
            d.put("tools", tools);
            d.put("providerCount", tools.stream()
                    .map(t -> providerOf(String.valueOf(t.get("source"))))
                    .distinct()
                    .count());
            String diff = firstNonBlank(console.profilePrompt(domain),
                    promptProperties.profilePrompt(domain));
            d.put("prompt", diff == null ? "" : diff);
            d.put("hasPrompt", diff != null && !diff.isBlank());
            d.put("promptLength", diff == null ? 0 : diff.trim().length());
            d.put("preview", resolver.preview(domain, console));
            domains.add(d);
        }
        body.put("domains", domains);

        /* 拼接边界标记下发一份：管控台要实时预览"实际会生效的全文"，
           若前端自己抄一遍标记，两边一旦不一致就会出现"预览与实际不符"的隐蔽问题。 */
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("begin", ProfileSystemPromptResolver.PROFILE_BEGIN);
        boundary.put("end", ProfileSystemPromptResolver.PROFILE_END);
        body.put("previewBoundary", boundary);

        List<String> orphans = new ArrayList<>(console.getProfiles().keySet());
        orphans.removeAll(known);
        Collections.sort(orphans);
        body.put("orphanPrompts", orphans);

        body.put("settingsFile", profileSettingsStore.filePath());
        return body;
    }

    /**
     * 保存提示词设定，立即生效（不重启）
     */
    @PostMapping("/profiles")
    public Map<String, Object> saveProfiles(@RequestBody(required = false) ProfileSettings incoming) {
        ProfileSettings toSave = normalize(incoming);
        log.info("[管控] 收到域提示词保存请求: 基线 {} 字符，域差异 {} 个",
                toSave.getBase() == null ? 0 : toSave.getBase().length(),
                toSave.getProfiles().size());
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            profileSettingsStore.save(toSave);
            result.put("success", true);
            result.put("message", "已保存并生效：下一次对话即使用新提示词");
            result.put("settingsFile", profileSettingsStore.filePath());
        } catch (Exception e) {
            log.error("[管控] 域提示词保存失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 归一化后再落盘。
     */
    private static ProfileSettings normalize(ProfileSettings incoming) {
        ProfileSettings result = new ProfileSettings();
        if (incoming == null) {
            return result;
        }
        if (incoming.getBase() != null && !incoming.getBase().isBlank()) {
            result.setBase(incoming.getBase());
        }
        if (incoming.getProfiles() != null) {
            incoming.getProfiles().forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    result.getProfiles().put(k.trim(), v);
                }
            });
        }
        return result;
    }

    /** 控制台值优先，未配置时回落 yaml（编辑框要显示"实际会生效的那份"） */
    private Map<String, String> effectiveProfileMap(ProfileSettings console) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (promptProperties.getProfiles() != null) {
            merged.putAll(promptProperties.getProfiles());
        }
        console.getProfiles().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                merged.put(k, v);
            }
        });
        // 存在的域即使没配提示词也要出现，否则前端渲染不出"待补提示词"的行
        for (String domain : toolRouter.getKnownProfiles()) {
            merged.putIfAbsent(domain, "");
        }
        return merged;
    }

    private static String firstNonBlank(String consoleValue, String yamlValue) {
        return consoleValue == null || consoleValue.isBlank() ? yamlValue : consoleValue;
    }

    /** 从 {@code com.foo.Bean#method} 取提供者部分，用于"这个域由谁贡献"的分组展示 */
    private static String providerOf(String source) {
        if (source == null || source.isBlank()) {
            return "-";
        }
        int hash = source.indexOf('#');
        return hash > 0 ? source.substring(0, hash) : source;
    }

    private static String text(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
