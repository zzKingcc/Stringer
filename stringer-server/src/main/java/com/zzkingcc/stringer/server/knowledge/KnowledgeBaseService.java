package com.zzkingcc.stringer.server.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzkingcc.stringer.api.agent.Domains;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import com.zzkingcc.stringer.infrastructure.elasticsearch.EsIndexManager;
import com.zzkingcc.stringer.infrastructure.ingestion.DocumentIngestor;
import com.zzkingcc.stringer.server.config.RagProperties;
import com.zzkingcc.stringer.server.settings.LlmModelHolder;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库服务：文档上传 / 列表 / 删除 / 状态。
 * @author zzkingcc
 */
@Slf4j
@Component
public class KnowledgeBaseService {

    /**
     * 列表聚合时单次最多拉取的命中数。
     */
    private static final int LIST_MAX_SIZE = 1000;

    private final ElasticsearchClient esClient;
    private final EmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final ThreadPoolExecutor ingestExecutor;
    private final Semaphore ingestPermit = new Semaphore(1);

    public KnowledgeBaseService(@Qualifier("stringerElasticsearchClient") ElasticsearchClient esClient,
                                @Qualifier("myEmbeddingStore") EmbeddingStore embeddingStore,
                                @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                                RagProperties ragProperties,
                                LlmModelHolder modelHolder) {
        this.esClient = esClient;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        AtomicLong seq = new AtomicLong();
        this.ingestExecutor = new ThreadPoolExecutor(
                ragProperties.getIngestPoolSize(),
                ragProperties.getIngestPoolSize(),
                60L, TimeUnit.SECONDS,
                // 有界队列：满即拒绝，绝不无界堆积
                new LinkedBlockingQueue<>(ragProperties.getIngestQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "stringer-kb-ingest-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[知识库] 导入线程池已初始化：size={}, queueCapacity={}",
                ragProperties.getIngestPoolSize(), ragProperties.getIngestQueueCapacity());
    }

    /** 进程退出时不再接受新任务，让在途导入收尾 */
    @PreDestroy
    public void shutdown() {
        ingestExecutor.shutdown();
    }

    /** 上传结果 */
    public record UploadResult(String docId, String fileName, long size, int chunks) {}

    /** 索引内已入库的文档条目 */
    public record DocumentItem(String docId, String fileName, String fileNameLower,
                               int chunks, List<String> domains) {}

    /**
     * 规范化文档的可用域 —— 与 {@code @Tool(domains = {...})} <b>同构</b>：
     * 每项都是从根域出发的完整路径，留空 = 挂在根域（按累加语义对全树可见）。
     *
     * <p>同构是刻意的：接入方学一次规则，工具与知识库两个维度通用。
     * 路径非法<b>直接抛异常</b>而不是静默丢弃 —— 静默会把文档写到一个树里不存在的域上，
     * 结果是永远检索不到且不报错。</p>
     */
    public static List<String> normalizeDomains(List<String> domains) {
        Set<String> seen = new LinkedHashSet<>();
        if (domains != null) {
            for (String domain : domains) {
                if (domain == null || domain.isBlank()) {
                    continue;
                }
                String path = domain.trim();
                String reason = Domains.validatePath(path);
                if (reason != null) {
                    throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER,
                            "知识库文档的域不合法（" + reason + "）；须为从 " + Domains.DEFAULT
                                    + " 出发的完整路径，如 default.sales");
                }
                seen.add(path);
            }
        }
        return seen.isEmpty() ? List.of(Domains.DEFAULT) : List.copyOf(seen);
    }

    /**
     * 同步上传一个文档（不声明域 → 只属兜底域 {@code default}）。
     *
     * @param content  文件字节
     * @param fileName 原始文件名（含扩展名）
     * @param replace  {@code true} = 已存在同名文档时先删旧再写入；{@code false} = 直接拒绝
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace) {
        return upload(content, fileName, replace, List.of());
    }

    /**
     * 同步上传一个文档，并声明它的可用域。
     *
     * <p>域决定<b>哪些对话能检索到这份文档</b>。检索工具本身仍由部署方用 {@code @Tool} 暴露到哪些域，
     * 两层是叠加的：工具不在该域 → 根本不会被调用；工具在该域 → 再按文档的域过滤内容。</p>
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, List<String> domains) {
        String name = requireSupported(fileName);
        if (content == null || content.length == 0) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件内容为空");
        }
        long max = ragProperties.getMaxFileSize().toBytes();
        if (content.length > max) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "文件 " + name + " 超过大小上限：" + content.length + " 字节，上限 " + max + " 字节");
        }
        List<String> effective = normalizeDomains(domains);
        log.info("[知识库] 收到上传请求：文件={}，大小={} 字节，replace={}，可用域={}",
                name, content.length, replace, effective);

        Future<UploadResult> future = submit(content, name, replace, effective);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR, "上传被中断", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof KnowledgeBaseException kbe) {
                throw kbe;
            }
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "知识库文档导入失败：" + cause.getMessage(), cause);
        }
    }

    /** 提交导入任务；队列满时明确拒绝，而不是无界堆积 */
    private Future<UploadResult> submit(byte[] content, String fileName, boolean replace, List<String> domains) {
        try {
            return ingestExecutor.submit(() -> ingest(content, fileName, replace, domains));
        } catch (RejectedExecutionException e) {
            log.warn("[知识库] 导入队列已满（容量 {}），拒绝本次上传：{}",
                    ragProperties.getIngestQueueCapacity(), fileName);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "知识库导入繁忙：队列已满（容量 " + ragProperties.getIngestQueueCapacity()
                            + "），请稍后重试");
        }
    }

    /** 已入库文档列表（按 doc_id 聚合出切片数；超出 {@link #LIST_MAX_SIZE} 的切片不计入展示） */
    public List<DocumentItem> list() {
        String index = ragProperties.getIndexName();
        if (!indexExists(index)) {
            return List.of();
        }
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(index)
                    .size(LIST_MAX_SIZE)
                    .query(Query.of(q -> q.matchAll(m -> m)))
                    .source(src -> src.filter(f -> f.includes("metadata"))), Map.class);

            Map<String, String> idToName = new LinkedHashMap<>();
            Map<String, Integer> idToChunks = new LinkedHashMap<>();
            Map<String, List<String>> idToDomains = new LinkedHashMap<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> md = metadataOf(hit.source());
                if (md == null) {
                    continue;
                }
                String docId = text(md.get("doc_id"));
                if (docId == null) {
                    continue;
                }
                idToName.putIfAbsent(docId, text(md.get("file_name")));
                idToChunks.merge(docId, 1, Integer::sum);
                idToDomains.putIfAbsent(docId, domainsOf(md));
            }
            List<DocumentItem> items = new ArrayList<>();
            idToName.forEach((id, fileName) ->
                    items.add(new DocumentItem(id, fileName, lower(fileName), idToChunks.getOrDefault(id, 0),
                            idToDomains.getOrDefault(id, List.of(Domains.DEFAULT)))));
            return items;
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "读取知识库文档列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 删除一个文档的全部切片，并释放其文件名（删除后同名可以再次上传）。
     *
     * @return 是否命中并删除
     */
    public boolean delete(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.INVALID_PARAMETER, "docId 不能为空");
        }
        String index = ragProperties.getIndexName();
        if (!indexExists(index) || countByDocId(index, docId) <= 0) {
            return false;
        }
        deleteByDocId(index, docId);
        log.info("[知识库] 已删除文档 docId={}（文件名随之释放）", docId);
        return true;
    }

    /** 索引状态：是否存在、切片总数、文档数（切片总数用 count 精确取，不受列表上限影响） */
    public Map<String, Object> status() {
        String index = ragProperties.getIndexName();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        boolean exists = indexExists(index);
        result.put("indexExists", exists);
        if (!exists) {
            result.put("documents", 0);
            result.put("chunks", 0);
            result.put("hint", "索引不存在，上传文档或触发重建后会自动创建");
            return result;
        }
        result.put("documents", list().size());
        result.put("chunks", countAll(index));
        return result;
    }

    // ==================== 内部实现 ====================

    private UploadResult ingest(byte[] content, String fileName, boolean replace,
                                List<String> domains) throws InterruptedException {
        // 串行锁在任务内部获取：排队等待不占用额外池线程，池大小可保持很小
        if (!ingestPermit.tryAcquire(ragProperties.getIngestLockWaitSeconds(), TimeUnit.SECONDS)) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "知识库导入繁忙：等待超过 " + ragProperties.getIngestLockWaitSeconds()
                            + " 秒仍未拿到导入锁，请稍后重试");
        }
        String docId = null;
        String index = null;
        try {
            index = ensureIndex();
            String lowerName = lower(fileName);
            List<DocumentItem> existing = list().stream()
                    .filter(d -> lowerName.equals(d.fileNameLower()))
                    .toList();
            if (!existing.isEmpty()) {
                if (!replace) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE,
                            "已存在同名文档：" + fileName + "（同名判定不区分大小写）。"
                                    + "如需替换请带 replace=true，或先删除原文档");
                }
                for (DocumentItem old : existing) {
                    deleteByDocId(index, old.docId());
                }
                log.info("[知识库] 覆盖更新：已清除同名旧文档 {} 个", existing.size());
            }

            docId = UUID.randomUUID().toString();
            Document doc = buildDocument(content, fileName, docId, lowerName, domains);
            int processed = DocumentIngestor.ingestExternalDocuments(
                    List.of(doc), esClient, index, embeddingStore, embeddingModel);
            if (processed <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "文档未处理成功：" + fileName);
            }
            int chunks = countByDocId(index, docId);
            if (chunks <= 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                        "导入后未写入任何片段，可能文档内容为空或向量化失败：" + fileName);
            }
            log.info("[知识库] 上传完成：文件={}，docId={}，切片={}", fileName, docId, chunks);
            return new UploadResult(docId, fileName, content.length, chunks);
        } catch (KnowledgeBaseException e) {
            rollback(index, docId);
            throw e;
        } catch (Exception e) {
            rollback(index, docId);
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_INGEST_ERROR,
                    "知识库文档导入失败：" + e.getMessage(), e);
        } finally {
            ingestPermit.release();
        }
    }

    /**
     * 失败回滚：清掉本次已写入的片段。
     */
    private void rollback(String index, String docId) {
        if (index == null || docId == null) {
            return;
        }
        try {
            deleteByDocId(index, docId);
            log.warn("[知识库] 导入失败已回滚，清除 docId={} 的残留片段", docId);
        } catch (Exception e) {
            log.error("[知识库] 回滚失败（docId={}），残留片段需手工清理: {}", docId, e.getMessage());
        }
    }

    /** 索引不存在则创建（维度取三处同源的公共取值点） */
    private String ensureIndex() {
        String index = ragProperties.getIndexName();
        int dimensions = modelHolder.effectiveEmbeddingDimension();
        EsIndexManager.createIndexWithIkMapping(esClient, index, dimensions);
        return index;
    }

    private Document buildDocument(byte[] content, String fileName, String docId,
                                   String lowerName, List<String> domains) {
        Document doc = Document.from(new String(content, StandardCharsets.UTF_8));
        Metadata md = doc.metadata();
        md.put("file_name", fileName);
        md.put("file_name_lower", lowerName);
        md.put("doc_id", docId);
        md.put("upload_time", Instant.now().toString());
        // 列表是 ES 过滤需要的形态；Metadata 没有 put(String,Object) 重载，只能走 putAll
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("domains", domains);
        md.putAll(extra);
        return doc;
    }

    /**
     * 读取切片元数据里的可用域；与工具声明同构：<b>没有该字段（或为空）即只属于兜底域
     * {@code default}</b>，全域可见必须显式声明通配 {@code "*"}。
     */
    static List<String> domainsOf(Map<String, Object> md) {
        Object raw = md.get("domains");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) {
                    out.add(value.toString());
                }
            }
            if (!out.isEmpty()) {
                return List.copyOf(out);
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            return List.of(raw.toString());
        }
        return List.of(Domains.DEFAULT);
    }

    /** 校验扩展名在白名单内，返回去空白的文件名 */
    private String requireSupported(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件名不能为空");
        }
        String name = fileName.trim();
        String ext = extensionOf(name);
        if (ext == null || !ragProperties.getAllowedExtensions()
                .contains(ext.toLowerCase(Locale.ROOT))) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "不支持的文件类型：" + name + "；当前白名单 " + ragProperties.getAllowedExtensions());
        }
        return name;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1);
    }

    private void deleteByDocId(String index, String docId) {
        try {
            esClient.deleteByQuery(d -> d.index(index)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId)))));
            esClient.indices().refresh(r -> r.index(index));
        } catch (Exception e) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除知识库文档失败：" + e.getMessage(), e);
        }
    }

    private int countByDocId(String index, String docId) {
        try {
            return (int) esClient.count(c -> c.index(index)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计 docId={} 的片段数失败: {}", docId, e.getMessage());
            return 0;
        }
    }

    private int countAll(String index) {
        try {
            return (int) esClient.count(c -> c.index(index)).count();
        } catch (Exception e) {
            log.warn("[知识库] 统计索引总片段数失败（index={}）: {}", index, e.getMessage());
            return 0;
        }
    }

    private boolean indexExists(String index) {
        try {
            return esClient.indices().exists(e -> e.index(index)).value();
        } catch (Exception e) {
            log.warn("[知识库] 判断索引是否存在失败（index={}）: {}", index, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataOf(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object md = source.get("metadata");
        return md instanceof Map ? (Map<String, Object>) md : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
