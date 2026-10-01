package com.zzkingcc.stringer.domain.rag.fusion;

/**
 * 融合排序参数
 *
 * @author zzkingcc
 * @param vectorWeight   向量权重（多索引时按「向量排名表个数」均摊到各表）
 * @param keywordWeight  关键词权重（多索引时按「关键词排名表个数」均摊到各表）
 * @param titleBoost     标题命中加分
 * @param fileNameBoost  文件名命中加分
 * @param topN           重排后返回条数
 * @param rrfK           RRF 平滑常数（多索引排名制融合用，默认 60）
 */
public record FusionConfig(double vectorWeight,
                           double keywordWeight,
                           double titleBoost,
                           double fileNameBoost,
                           int topN,
                           int rrfK) {

    /** 默认配置:向量 0.6 / 关键词 0.4,标题 +0.15,文件名 +0.10,返回 Top10,RRF k=60 */
    public static FusionConfig defaults() {
        return new FusionConfig(0.6, 0.4, 0.15, 0.10, 10, 60);
    }
}
