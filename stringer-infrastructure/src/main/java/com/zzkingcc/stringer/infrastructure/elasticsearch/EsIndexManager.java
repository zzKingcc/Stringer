package com.zzkingcc.stringer.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量索引操作工具
 * @author zzkingcc
 */
@Slf4j
public class EsIndexManager {

    /**
     * 切片元数据里"可用域"的字段名（与 {@code @Tool(domains = {...})} 同构：含 {@code *} → 全域；留空 → 兜底域）。
     *
     * <p>必须是 {@code keyword}：动态映射会把它变成 {@code text}（默认分词器），
     * 而 {@code "*"} 是纯标点会被分词器丢掉，"全域可见"的文档就永远查不到。</p>
     */
    public static final String DOMAINS_FIELD = "domains";

    /** ES 查询路径 */
    public static final String DOMAINS_QUERY_FIELD = "metadata." + DOMAINS_FIELD;

    private EsIndexManager() {}

    /** 启动诊断：输出 ES 版本、集群健康、索引存在性及字段结构 */
    public static void diagnoseElasticsearch(ElasticsearchClient esClient, String indexName) {
        try {
            var info = esClient.info();
            String version = info.version().number();
            String clusterName = info.clusterName();
            log.info("=================== ES 诊断开始 ===================");
            log.info("[ES] 集群: {}  |  服务器版本: {}", clusterName, version);

            HealthResponse h = esClient.cluster().health();
            log.info("[ES] 集群健康: status={}, 节点数={}, 活跃分片={}",
                    h.status(), h.numberOfNodes(), h.activeShards());

            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            log.info("[ES] 索引[{}] 是否存在: {}", indexName, exists);
            if (exists) {
                try {
                    SearchResponse<Map> s = esClient.search(q -> q.index(indexName).size(0), Map.class);
                    log.info("[ES] 索引文档数: {}", s.hits().total() != null ? s.hits().total().value() : "N/A");
                } catch (Exception ignored) {
                }
                try {
                    IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                    if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                        Map<String, Property> properties = state.mappings().properties();
                        log.info("[ES] Mapping 字段:");
                        properties.forEach((name, prop) -> {
                            String extra = "";
                            if (prop.isDenseVector()) {
                                extra = " (dims=" + prop.denseVector().dims() + ")";
                            } else if (prop.isObject()) {
                                extra = " (enabled=" + (prop.object().enabled() != null && Boolean.TRUE.equals(prop.object().enabled())) + ")";
                            }
                            log.info("[ES]      - {} -> {}{}", name, propertyTypeName(prop), extra);
                        });
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("=================== ES 诊断结束 ===================");
        } catch (Exception e) {
            log.error("[ES] 诊断失败: {}", e.getMessage(), e);
        }
    }

    /** 按需删除旧索引 */
    public static void deleteIndexIfNeeded(ElasticsearchClient esClient, String indexName, boolean deleteOnStartup) {
        if (!deleteOnStartup) return;
        try {
            if (esClient.indices().exists(r -> r.index(indexName)).value()) {
                esClient.indices().delete(d -> d.index(indexName));
                log.warn("[ES] 已删除旧索引: {}", indexName);
            }
        } catch (Exception e) {
            log.warn("[ES] 删除旧索引[{}]失败，将继续重建流程：{}", indexName, e.getMessage());
        }
    }

    /**
     * 读取索引当前的向量维度。
     */
    public static Integer currentVectorDims(ElasticsearchClient esClient, String indexName) {
        try {
            IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
            if (state == null || state.mappings() == null) {
                return null;
            }
            Property vector = state.mappings().properties().get("vector");
            if (vector == null || !vector.isDenseVector()) {
                return null;
            }
            return vector.denseVector().dims();
        } catch (Exception e) {
            log.debug("[ES] 读取索引[{}]向量维度失败: {}", indexName, e.getMessage());
            return null;
        }
    }

    /**
     * 创建带 IK 分词器的索引 mapping。
     *
     * @param esClient  ES 客户端
     * @param indexName 索引名
     * @param dims      向量维度（来自用户声明或实测，不由调用方写死）
     */
    public static void createIndexWithIkMapping(ElasticsearchClient esClient, String indexName, int dims) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                Integer current = currentVectorDims(esClient, indexName);
                if (current != null && current != dims) {
                    log.error("[ES] 索引[{}]已存在且向量维度不一致：现有 mapping dims={}，当前配置需要 {}。"
                                    + "写入会因维度不符被拒绝，需删除索引后重建"
                                    + "（管控台「知识库」页点「重建索引」，或置 stringer.rag.es.delete-on-startup=true 后重启）。",
                            indexName, current, dims);
                } else {
                    log.info("[ES] 索引[{}]已存在（mapping dims={}），跳过创建IK mapping", indexName, current);
                }
                // 存量索引补字段：domains 是新增的（知识库按域检索），mapping 允许加新字段，不允许改旧字段类型
                ensureDomainsMapping(esClient, indexName);
                return;
            }

            // 使用原始 JSON 构建 mapping（Java API Builder 对 analyzer 支持不直观）
            String mappingJson = """
                    {
                      "mappings": {
                        "properties": {
                          "vector": {
                            "type": "dense_vector",
                            "dims": %d,
                            "index": true,
                            "similarity": "cosine"
                          },
                          "text": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart",
                            "fields": {
                              "keyword": {
                                "type": "keyword",
                                "ignore_above": 256
                              }
                            }
                          },
                          "metadata": {
                            "type": "object",
                            "enabled": true,
                            "properties": {
                              "file_name":     { "type": "keyword" },
                              "section_title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                              "content_hash":  { "type": "keyword" },
                              "domains":       { "type": "keyword" }
                            }
                          }
                        }
                      }
                    }
                    """.formatted(dims);

            esClient.indices().create(c -> c
                    .index(indexName)
                    .withJson(new java.io.StringReader(mappingJson)));

            log.info("[ES] 索引[{}]创建成功（IK分词器 mapping，dims={}）", indexName, dims);
        } catch (Exception e) {
            // 这里刻意不按报文关键字去分类异常（"是不是 IK 没装"）。
            // 判据靠关键字在多版本、多发行版的 ES 上并不可靠，而错误分类会把用户引向错误方向。
            // 如实说明降级已发生，并指出一条确定的确认途径：
            // 管控台「存储配置」页点一次「测试连接」，会直接告诉你 IK 是否可用。
            log.warn("""
                    ================ [ES] 索引创建降级 ================
                    索引[{}] 未能按 IK 分词器 mapping 创建，将由上层回退到默认创建方式。
                    影响：text 字段改用 ES 默认分词器，中文检索召回质量下降。
                          注意这不会报错、不会失败——索引照样建、灌库照样成功，只是结果变差。
                    最常见原因：该 ES 未安装 analysis-ik 插件（IK 是插件，任何版本都需单独安装）。
                    确认方式：管控台 http://localhost:9527/admin.html「存储配置」页点一次
                              「测试连接」，它会明确告知 IK 是否可用。
                    原始原因：{}
                    ===============================================
                    """, indexName, e.getMessage());
        }
    }

    /** 写入后校验：刷新索引，输出查询结果 */
    /**
     * 存量索引补 {@code metadata.domains} 字段（知识库按域检索需要）。
     *
     * <p>为什么必须显式声明为 {@code keyword}：靠动态映射的话，字符串会被映射成 {@code text}
     * （默认分词器），而 {@code "*"} 是纯标点、被分词器直接丢掉 —— {@code terms} 就永远匹配不上
     * "全域可见"的文档。加字段是 ES 允许的操作（改已有字段类型才不允许）。</p>
     */
    private static void ensureDomainsMapping(ElasticsearchClient esClient, String indexName) {
        try {
            var state = esClient.indices().get(g -> g.index(indexName)).get(indexName);
            if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                Property metadata = state.mappings().properties().get("metadata");
                if (metadata != null && metadata.isObject()
                        && metadata.object().properties().containsKey(DOMAINS_FIELD)) {
                    return;
                }
            }
            esClient.indices().putMapping(p -> p
                    .index(indexName)
                    .withJson(new java.io.StringReader(
                            "{\"properties\":{\"metadata\":{\"type\":\"object\","
                                    + "\"properties\":{\"" + DOMAINS_FIELD + "\":{\"type\":\"keyword\"}}}}}")));
            log.info("[ES] 索引[{}] 已补上 metadata.domains(keyword) 字段（知识库按域检索需要）", indexName);
        } catch (Exception e) {
            // 补字段失败不该阻断启动：过滤条件仍会生效，只是"全域可见"的文档可能查不到，留日志可查
            log.warn("[ES] 索引[{}] 补充 metadata.domains 字段失败，按域检索可能过滤不到文档: {}",
                    indexName, e.getMessage());
        }
    }

    public static void writeAfterVerify(ElasticsearchClient esClient, String indexName) {
        try {
            esClient.indices().refresh(r -> r.index(indexName));

            long count = esClient.count(c -> c.index(indexName)).count();
            log.info("================ [ES] 写入后校验 ================");
            log.info("[ES] 文档总数: {}", count);

            try {
                IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                    Map<String, Property> properties = state.mappings().properties();
                    log.info("[ES] Mapping 字段:");
                    properties.forEach((name, prop) -> {
                        String type = propertyTypeName(prop);
                        String dims = prop.isDenseVector() ? String.valueOf(prop.denseVector().dims()) : "";
                        log.info("[ES]      - {} -> type={}, dims={}", name, type, dims);
                    });
                }
            } catch (Exception ignored) {
            }

            SearchResponse<Map> searchResp = esClient.search(s -> s.index(indexName).size(1), Map.class);
            Map<String, Object> firstSource = null;
            if (searchResp.hits().hits() != null && !searchResp.hits().hits().isEmpty()) {
                firstSource = searchResp.hits().hits().get(0).source();
            }

            if (firstSource != null) {
                log.info("[ES] 首条文档字段: {}", firstSource.keySet());
                String vecField = null;
                String txtField = null;
                int vecLen = 0;
                Object[] vecArr = null;

                for (Map.Entry<String, Object> e : firstSource.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (v.getClass().isArray()) {
                        vecField = e.getKey();
                        if (v instanceof double[]) {
                            vecLen = ((double[]) v).length;
                            vecArr = toObjectArray((double[]) v);
                        } else if (v instanceof float[]) {
                            vecLen = ((float[]) v).length;
                            vecArr = toObjectArray((float[]) v);
                        } else if (v instanceof int[]) {
                            vecLen = ((int[]) v).length;
                        }
                        log.info("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof java.util.List) {
                        vecField = e.getKey();
                        vecLen = ((java.util.List<?>) v).size();
                        vecArr = ((java.util.List<?>) v).toArray();
                        log.info("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof String) {
                        String sv = (String) v;
                        String curTxt = (String) firstSource.get(txtField);
                        if (txtField == null || (curTxt != null && sv.length() > curTxt.length())) {
                            txtField = e.getKey();
                        }
                    }
                }

                if (txtField != null) {
                    String t = (String) firstSource.get(txtField);
                    if (t != null) {
                        log.info("[ES]      文本字段 = {}，前100字 = {}", txtField,
                                t.length() > 100 ? t.substring(0, 100) + "..." : t);
                    }
                }

                if (vecField != null && vecArr != null && count > 0) {
                    try {
                        String finalVecField = vecField;
                        String finalTxtField = txtField == null ? "text" : txtField;

                        Map<String, JsonData> params = new HashMap<>();
                        params.put("q", JsonData.of(vecArr));

                        co.elastic.clients.elasticsearch._types.Script script =
                                new co.elastic.clients.elasticsearch._types.Script.Builder()
                                        .source(ss -> ss.scriptString(
                                                "cosineSimilarity(params.q, '" + finalVecField + "') + 1.0"))
                                        .params(params)
                                        .build();

                        co.elastic.clients.elasticsearch._types.query_dsl.Query scriptScoreQuery =
                                new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder()
                                        .scriptScore(ss -> ss
                                                .query(q -> q.matchAll(m -> m))
                                                .script(script))
                                        .build();

                        SearchResponse<Map> exampleResp = esClient.search(s -> s
                                        .index(indexName)
                                        .size(2)
                                        .source(src -> src.filter(f -> f.includes(finalTxtField)))
                                        .query(scriptScoreQuery),
                                Map.class);
                        long total = exampleResp.hits().total() != null ? exampleResp.hits().total().value() : 0;
                        log.info("[ES] script_score 示例查询通过，命中 = {}", total);
                    } catch (Exception e) {
                        log.warn("[ES] script_score 示例查询失败: {}", e.getMessage());
                    }
                }
            } else {
                log.info("[ES] 未查询到首条文档，size(0) total = {}",
                        searchResp.hits().total() != null ? searchResp.hits().total().value() : 0);
            }
            log.info("================ [ES] 写入后校验结束 ================");
        } catch (Exception e) {
            log.error("[ES] 写入后校验异常: {}", e.getMessage(), e);
        }
    }

    private static Object[] toObjectArray(double[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    private static Object[] toObjectArray(float[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = (double) arr[i];
        return out;
    }

    /** 将 ES Property 转为可读的字段类型名称 */
    private static String propertyTypeName(Property p) {
        if (p == null) return "null";
        if (p.isText()) return "text";
        if (p.isKeyword()) return "keyword";
        if (p.isDenseVector()) return "dense_vector";
        if (p.isObject()) return "object";
        if (p.isNested()) return "nested";
        if (p.isInteger() || p.isLong()) return "long";
        if (p.isFloat()) return "float";
        if (p.isDouble()) return "double";
        if (p.isBoolean()) return "boolean";
        if (p.isIp()) return "ip";
        if (p.isDate()) return "date";
        if (p.isGeoPoint()) return "geo_point";
        return p._kind().name().toLowerCase();
    }
}
