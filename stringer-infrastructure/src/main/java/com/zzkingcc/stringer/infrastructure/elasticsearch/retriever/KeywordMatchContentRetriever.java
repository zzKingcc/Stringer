package com.zzkingcc.stringer.infrastructure.elasticsearch.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 关键词精准匹配检索器（BM25）
 *
 * <p>多字段权重一次性表达"哪里命中更重要"，而不是把字段拆成多张排名表 ——
 * 表数保持 = 索引 × 模态，融合阶段的按模态均摊权重才不会被字段数稀释。</p>
 *
 * @author zzkingcc
 */
public class KeywordMatchContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordMatchContentRetriever.class);

    /**
     * 参与 BM25 的字段与权重。
     *
     * <ul>
     *   <li>{@code text} —— 正文（切片时已把 section_path 前置进正文，标题词天然在内）；</li>
     *   <li>{@code text.standard} —— 标准分词，救英文/数字/标识符（ik 会把它们切碎）；</li>
     *   <li>{@code metadata.section_path} —— 完整层级路径，命中说明整条上下文相关；</li>
     *   <li>{@code metadata.section_title} —— 末级标题，最直接的"这条讲的就是这个"。</li>
     * </ul>
     */
    private static final List<String> SEARCH_FIELDS = List.of(
            "text^1.0",
            "text.standard^0.8",
            "metadata.section_path^1.5",
            "metadata.section_title^2.0"
    );

    private final ElasticsearchClient esClient;
    private final String indexName;
    private final int maxResults;
    private final String minimumShouldMatch;

    public KeywordMatchContentRetriever(
            ElasticsearchClient esClient,
            String indexName,
            int maxResults,
            String minimumShouldMatch) {
        this.esClient = esClient;
        this.indexName = indexName;
        this.maxResults = maxResults;
        this.minimumShouldMatch = minimumShouldMatch;
    }

    @Override
    public List<Content> retrieve(dev.langchain4j.rag.query.Query query) {
        String queryText = query.text();

        Query matchQuery = new Query.Builder()
                .multiMatch(m -> {
                    m.query(queryText)
                            .fields(SEARCH_FIELDS)
                            .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields);
                    // 压单字命中噪音：查询被切成 N 个词时，至少要命中这么多个才算一次匹配，
                    // 否则"会"、"在"这类单字就能把整库文档拉回来。
                    if (minimumShouldMatch != null && !minimumShouldMatch.isBlank()) {
                        m.minimumShouldMatch(minimumShouldMatch);
                    }
                    return m;
                })
                .build();

        try {
            // 一域一索引：域边界由「查哪个索引」保证，查询里不含 metadata.domains 过滤。
            // allowNoIndices / ignoreUnavailable：域链上某个祖先域可能还没有索引，此时应安静地返回空，
            // 而不是抛 index_not_found 把整轮检索打断。
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(indexName)
                            .ignoreUnavailable(true)
                            .allowNoIndices(true)
                            .size(maxResults)
                            .source(src -> src.filter(f -> f.includes("text", "metadata")))
                            .query(matchQuery),
                    Map.class);

            List<Content> out = new ArrayList<>();
            resp.hits().hits().forEach(h -> {
                String text = "";
                if (h.source() != null && h.source().get("text") != null) {
                    text = h.source().get("text").toString();
                }
                if (!text.isBlank()) {
                    TextSegment segment = TextSegment.from(text);
                    // 将 ES BM25 分数写入 metadata，供后续分数融合使用
                    segment.metadata().put("_retrieval_score", h.score());
                    // 将 ES 元数据字段传递给上层，供 boost 计算使用
                    copyEsMetadata(h.source(), segment);
                    out.add(Content.from(segment));
                }
            });

            log.info("[ES关键词检索] 查询完成，关键词='{}'，设定{}条，命中{}条",
                    queryText.length() > 50 ? queryText.substring(0, 50) + "..." : queryText,
                    maxResults, out.size());
            return out;
        } catch (IOException e) {
            log.error("[ES关键词检索] 查询异常: {}", e.getMessage(), e);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_SEARCH_ERROR, "ES 关键词检索异常: " + e.getMessage(), e);
        }
    }

    /**
     * 从 ES source 中提取 file_name、section_title 等元数据写入 TextSegment metadata
     */
    @SuppressWarnings("unchecked")
    private void copyEsMetadata(Map<String, Object> source, TextSegment segment) {
        if (source == null) return;
        Object metadataObj = source.get("metadata");
        if (metadataObj instanceof Map) {
            Map<String, Object> meta = (Map<String, Object>) metadataObj;
            if (meta.get("file_name") != null) {
                segment.metadata().put("file_name", meta.get("file_name").toString());
            }
            if (meta.get("section_title") != null) {
                segment.metadata().put("section_title", meta.get("section_title").toString());
            }
        }
    }
}
