package com.zzkingcc.stringer.server.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.stringer.domain.rag.fusion.AdaptiveFusionStrategy;
import com.zzkingcc.stringer.domain.rag.fusion.FusionConfig;
import com.zzkingcc.stringer.domain.rag.retriever.CompositeRetriever;
import com.zzkingcc.stringer.server.knowledge.DomainChannelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检索配置：混合检索的编排与融合。
 *
 * <p>召回通道不是固定的两路，而是<b>按当前域动态解析</b>：一域一索引，检索域 D 时
 * 对 D 自身与祖先链上的每个索引各召回「向量 + 关键词」两路（见 {@link DomainChannelProvider}）。
 * 域链越长通道越多，因此融合必须能在"单索引"与"多索引"之间切换：</p>
 * <ul>
 *   <li>只有一个索引 → {@code DefaultFusionStrategy}（分数制，与升级前逐字节一致）；</li>
 *   <li>沿父类链取到多个索引 → {@code RrfFusionStrategy}（排名制，规避 BM25 跨索引不可比）。</li>
 * </ul>
 * <p>{@link AdaptiveFusionStrategy} 就是这条双轨的自动切换入口。</p>
 *
 * <p>知识库索引不再在启动期创建：域是运行期由用户创建的，索引跟着域走，在首次上传时按需建出
 * （见 {@code KnowledgeBaseService#ensureIndex}）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({RagProperties.class, RetrievalProperties.class})
public class RetrievalConfiguration {

    /**
     * 混合检索专用线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService retrievalExecutor(RetrievalProperties retrievalProps) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "stringer-retrieval-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                retrievalProps.getCorePoolSize(),
                retrievalProps.getMaxPoolSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(retrievalProps.getQueueCapacity()),
                factory);

        log.info("[检索配置] 检索线程池 core={}, max={}, queue={}, parallel={}, timeout={}ms",
                retrievalProps.getCorePoolSize(), retrievalProps.getMaxPoolSize(),
                retrievalProps.getQueueCapacity(), retrievalProps.isParallel(),
                retrievalProps.getTimeoutMs());
        return executor;
    }

    /**
     * 组合检索器：按当前域解析通道（域链上每个索引 × 2 路）→ 召回编排 → 双轨融合重排。
     */
    @Bean
    @Lazy
    public ContentRetriever myContentRetriever(
            @Qualifier("stringerElasticsearchClient") ElasticsearchClient esClient,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
            RetrievalProperties retrievalProps,
            @Qualifier("retrievalExecutor") ExecutorService retrievalExecutor) {

        FusionConfig fusion = new FusionConfig(
                retrievalProps.getVectorWeight(),
                retrievalProps.getKeywordWeight(),
                retrievalProps.getTitleBoost(),
                retrievalProps.getFileNameBoost(),
                retrievalProps.getTopN(),
                retrievalProps.getRrfK());

        // parallel=false 时传 null,检索器退化为串行召回
        Executor executor = retrievalProps.isParallel() ? retrievalExecutor : null;

        log.info("[检索配置] 混合检索：向量 Top{}（minScore {}）/ 关键词 Top{}，融合 topN={}，RRF k={}",
                retrievalProps.getVectorTopK(), retrievalProps.getVectorMinScore(),
                retrievalProps.getKeywordTopK(), retrievalProps.getTopN(), retrievalProps.getRrfK());

        return new CompositeRetriever(
                new DomainChannelProvider(esClient, embeddingModel, retrievalProps),
                fusion, executor, retrievalProps.getTimeoutMs(), new AdaptiveFusionStrategy());
    }
}
