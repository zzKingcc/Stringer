package com.zzkingcc.stringer.domain.rag.retriever;

import com.zzkingcc.stringer.domain.rag.fusion.DefaultFusionStrategy;
import com.zzkingcc.stringer.domain.rag.fusion.FusionConfig;
import com.zzkingcc.stringer.domain.rag.fusion.FusionStrategy;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 混合检索器：编排两路召回（向量 + 关键词，可串行 / 并行 + 超时），失败互不影响，
 * 融合重排交给 {@link FusionStrategy}（默认 {@link DefaultFusionStrategy}）。
 *
 * <p>本类只负责"召回编排 + 失败处理"，不内联融合算法 —— 这样部署方可以注入自定义的
 * {@link FusionStrategy}（如 RRF、rerank）而不必改动召回逻辑。</p>
 *
 * @author zzkingcc
 */
public class CompositeRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(CompositeRetriever.class);

    private final ContentRetriever vectorRetriever;
    private final ContentRetriever keywordRetriever;
    private final FusionConfig fusion;
    private final FusionStrategy fusionStrategy;
    /** 并行召回线程池;null 表示串行执行 */
    private final Executor executor;
    /** 单路召回超时(毫秒);&lt;=0 表示不限时 */
    private final long timeoutMs;

    public CompositeRetriever(ContentRetriever vectorRetriever,
                              ContentRetriever keywordRetriever) {
        this(vectorRetriever, keywordRetriever, FusionConfig.defaults(), null, 0L, null);
    }

    public CompositeRetriever(ContentRetriever vectorRetriever,
                              ContentRetriever keywordRetriever,
                              FusionConfig fusion,
                              Executor executor,
                              long timeoutMs) {
        this(vectorRetriever, keywordRetriever, fusion, executor, timeoutMs, null);
    }

    /**
     * 全参构造器：部署方可传入自定义 {@link FusionStrategy} 替换融合算法。
     *
     * @param fusionStrategy 融合策略；{@code null} 时使用 {@link DefaultFusionStrategy}
     */
    public CompositeRetriever(ContentRetriever vectorRetriever,
                              ContentRetriever keywordRetriever,
                              FusionConfig fusion,
                              Executor executor,
                              long timeoutMs,
                              FusionStrategy fusionStrategy) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.fusion = fusion == null ? FusionConfig.defaults() : fusion;
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.fusionStrategy = fusionStrategy == null ? new DefaultFusionStrategy() : fusionStrategy;
    }

    @Override
    public List<Content> retrieve(Query query) {
        long start = System.currentTimeMillis();

        ChannelResult vector;
        ChannelResult keyword;

        if (executor == null) {
            vector = retrieveSafely(vectorRetriever, query, "向量");
            keyword = retrieveSafely(keywordRetriever, query, "关键词");
        } else {
            CompletableFuture<List<Content>> vectorFuture =
                    CompletableFuture.supplyAsync(() -> vectorRetriever.retrieve(query), executor);
            CompletableFuture<List<Content>> keywordFuture =
                    CompletableFuture.supplyAsync(() -> keywordRetriever.retrieve(query), executor);
            vector = await(vectorFuture, "向量");
            keyword = await(keywordFuture, "关键词");
        }

        if (vector.failed() && keyword.failed()) {
            // 注意！两路都失败不一定没有相关内容：这里若返回空列表，"检索链路坏了"就和"真没命中"
            // 长得一模一样——调用方会换个问法反复试，而不是去查配置。
            throw new IllegalStateException("向量检索与关键词检索均失败，无法判断是否存在相关内容");
        }

        List<Content> result = fusionStrategy.fuse(query.text(),
                vector.contents(), keyword.contents(), fusion);
        log.info("[组合检索] 融合耗时 {}ms", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 单路召回结果
     */
    private record ChannelResult(List<Content> contents, boolean failed) {

        static ChannelResult ok(List<Content> contents) {
            return new ChannelResult(contents == null ? List.of() : contents, false);
        }

        static ChannelResult failure() {
            return new ChannelResult(List.of(), true);
        }
    }

    /**
     * 串行模式下的安全召回:单路异常不影响另一路
     */
    private ChannelResult retrieveSafely(ContentRetriever retriever, Query query, String channel) {
        try {
            return ChannelResult.ok(retriever.retrieve(query));
        } catch (Exception e) {
            log.warn("[组合检索] {}检索异常,本次降级为仅用另一路结果: {}", channel, e.getMessage());
            return ChannelResult.failure();
        }
    }

    /**
     * 并行模式下等待单路结果:超时或异常都记为"该路失败",并取消该路任务
     */
    private ChannelResult await(CompletableFuture<List<Content>> future, String channel) {
        try {
            // TMD,timeoutMs 小于等于 0 时退化成无限等待 单路 ES 卡死会把整轮检索
            // 现在一起拖住，默认未配置时取 5s 兜底值。
            long effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : 5000L;
            List<Content> out = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            return ChannelResult.ok(out);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("[组合检索] {}检索失败(超时或异常),本次降级为仅用另一路结果: {}", channel, e.getMessage());
            return ChannelResult.failure();
        }
    }
}
