package com.zzkingcc.stringer.domain.rag.fusion;

import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 混合检索融合策略：把两路召回（向量 + 关键词）合并、去重、归一化、加权、重排。
 *
 * <p>从 {@code CompositeRetriever} 里抽出来的目的：让部署方可以替换融合算法
 * （例如 RRF、rerank），而不必改动召回编排与失败处理。默认实现
 * {@link DefaultFusionStrategy} 完全复刻升级前行为，<b>只抽取不改算法</b>。</p>
 *
 * @author zzkingcc
 */
public interface FusionStrategy {

    /**
     * 融合两路召回结果。
     *
     * @param queryText      原始查询文本（用于标题 / 文件名命中 boost 判定）
     * @param vectorResults  向量通道结果（可为 {@code null} / 空列表，表示本路失败或未命中）
     * @param keywordResults 关键词通道结果（可为 {@code null} / 空列表）
     * @param fusion         融合参数（权重 / boost / topN）
     * @return 融合重排后的结果，已回写分项分、融合分、名次、命中通道到 metadata
     */
    List<Content> fuse(String queryText,
                       List<Content> vectorResults,
                       List<Content> keywordResults,
                       FusionConfig fusion);
}
