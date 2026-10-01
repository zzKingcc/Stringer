package com.zzkingcc.stringer.server.config;

import com.zzkingcc.stringer.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.stringer.infrastructure.ingestion.txt.TxtChunking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code stringer.rag.chunking.*} 注入切片器的全局取值点。
 *
 * <p>需要这一层是因为策略工厂是静态的、策略实例是无参构造的单例，拿不到 Spring 的配置对象。</p>
 *
 * <p>两套切片器取同一份参数：{@code TxtChunking}（纯文本管线）与
 * {@code BlockChunking}（md / docx / pdf 的结构化管线）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
public class ChunkingConfiguration {

    public ChunkingConfiguration(RagProperties ragProperties) {
        RagProperties.Chunking chunking = ragProperties.getChunking();
        TxtChunking.configure(chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars());
        BlockChunking.configure(chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars());
        log.info("[知识库] 切片参数已就绪：max-chars={}，overlap-sentences={}，min-chars={}",
                chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars());
    }
}
