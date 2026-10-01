package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 未知/未识别文件类型处理策略
 * @author zzkingcc
 */
@Slf4j
public class UnknownDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final UnknownDocumentProcessStrategy INSTANCE = new UnknownDocumentProcessStrategy();

    private UnknownDocumentProcessStrategy() {
    }

    @Override
    public IngestReport process(List<Document> documents,
                                ElasticsearchClient esClient,
                                String indexName,
                                EmbeddingStore embeddingStore,
                                EmbeddingModel embeddingModel,
                                String sourceTag) {
        int docCount = documents.size();
        log.info("[分片写入-{}][{}] ⚠ 共[{}]个文件的扩展名未识别或未识别，当前版本暂不支持该类型，已跳过。",
                sourceTag, strategyName(), docCount);
        return IngestReport.EMPTY;
    }

    @Override
    public List<String> supportedExtensions() {
        // 兜底策略，不注册具体扩展名
        return List.of();
    }

    @Override
    public String strategyName() {
        return "未知类型";
    }

    private String safeFileName(Document doc) {
        try {
            //1、文件名
            String name = doc.metadata().getString("file_name");
            if (name != null && !name.isBlank()) return name;
            //2、来源路径截取
            String src = doc.metadata().getString("source");
            if (src != null && !src.isBlank()) {
                int sep = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
                return sep >= 0 ? src.substring(sep + 1) : src;
            }
            //3、绝对路径截取
            String abs = doc.metadata().getString("absolute_path");
            if (abs != null && !abs.isBlank()) {
                int sep = Math.max(abs.lastIndexOf('/'), abs.lastIndexOf('\\'));
                return sep >= 0 ? abs.substring(sep + 1) : abs;
            }
        } catch (Exception e) {
            log.error("提取文件名时出错", e);
        }
        return "(unknown)";
    }
}
