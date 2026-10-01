package com.zzkingcc.stringer.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 混合检索配置
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "stringer.retrieval")
public class RetrievalProperties {

    /** 各路检索是否并行执行;false 退化为串行 */
    private boolean parallel = true;

    /** 单路检索超时(毫秒);超时后该路降级为空结果 */
    private long timeoutMs = 5000;

    /** 检索线程池核心线程数 */
    private int corePoolSize = 4;

    /** 检索线程池最大线程数 */
    private int maxPoolSize = 16;

    /** 检索线程池队列容量 */
    private int queueCapacity = 200;

    /** 向量路每张索引召回条数 */
    private int vectorTopK = 15;

    /** 关键词路每张索引召回条数 */
    private int keywordTopK = 5;

    /** 向量路最低余弦相似度(偏差已 +1.0 回归,这里的值按原始余弦填) */
    private double vectorMinScore = 0.2;

    /** 融合权重:向量 */
    private double vectorWeight = 0.6;

    /** 融合权重:关键词 */
    private double keywordWeight = 0.4;

    /** 标题命中查询关键词时的加分 */
    private double titleBoost = 0.15;

    /** 文件名命中查询关键词时的加分 */
    private double fileNameBoost = 0.10;

    /** 融合重排后返回条数 */
    private int topN = 10;

    /** RRF 平滑常数(仅多索引排名制融合使用) */
    private int rrfK = 60;
}
