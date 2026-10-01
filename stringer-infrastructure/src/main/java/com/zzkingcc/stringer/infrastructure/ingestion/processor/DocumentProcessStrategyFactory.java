package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档策略匹配工厂
 * @author zzkingcc
 */
@Slf4j
public class DocumentProcessStrategyFactory {

    /** 已注册策略实例 */
    private static final List<DocumentProcessStrategy> ALL_STRATEGIES = List.of(
            new TextDocumentProcessStrategy(),          // .txt
            new MarkdownDocumentProcessStrategy(),      // .md .markdown
            new PdfDocumentProcessStrategy(),           // .pdf
            UnknownDocumentProcessStrategy.INSTANCE     // 未知文件类型
    );

    /** 扩展名 → 策略 的映射缓存 */
    private static final Map<String, DocumentProcessStrategy> EXT_TO_STRATEGY;

    static {
        EXT_TO_STRATEGY = new HashMap<>();
        for (DocumentProcessStrategy strategy : ALL_STRATEGIES) {
            for (String ext : strategy.supportedExtensions()) {
                String lowerExt = ext.toLowerCase(Locale.ROOT);
                DocumentProcessStrategy prev = EXT_TO_STRATEGY.put(lowerExt, strategy);
                if (prev != null) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_STRATEGY_NOT_FOUND,
                            "[策略工厂] 扩展名冲突: ." + lowerExt + " 同时被 ["
                                    + prev.strategyName() + "] 和 [" + strategy.strategyName() + "] 覆盖，请检查 supportedExtensions() 列表。");
                }
            }
        }
    }

    /**
     * 根据文件名取扩展名匹配策略。
     */
    public static DocumentProcessStrategy resolve(String fileName) {
        //扩展名提取
        String ext = extractExtension(fileName);
        if (ext == null) {
            // 超出扩展名边界，使用 unknown 策略
            return UnknownDocumentProcessStrategy.INSTANCE;
        }
        DocumentProcessStrategy s = EXT_TO_STRATEGY.get(ext.toLowerCase(Locale.ROOT));
        //当有效扩展名未适配策略时，使用 unknown 策略
        if (s != null) return s;
        return UnknownDocumentProcessStrategy.INSTANCE;
    }

    /**
     * 按照文档划分构建各处理器扫描链。
     * 
     * @param documents 待分组的 Document 列表
     * @return
     */
    public static Map<DocumentProcessStrategy, List<Document>> groupByStrategy(List<Document> documents) {
        Map<DocumentProcessStrategy, List<Document>> group = new LinkedHashMap<>();
        for (DocumentProcessStrategy s : ALL_STRATEGIES) {
            group.put(s, new ArrayList<>());
        }

        for (Document doc : documents) {
            String name = extractFileName(doc);
            DocumentProcessStrategy strategy = resolve(name);
            // group.computeIfAbsent(strategy, k -> new ArrayList<>()).add(doc);
            group.get(strategy).add(doc);
        }

        // 移除空组，减少上层循环输出
        group.entrySet().removeIf(e -> e.getValue().isEmpty());
        return group;
    }

    /**
     * 所有已注册的策略列表
     */
    public static List<DocumentProcessStrategy> allStrategies() {
        return ALL_STRATEGIES;
    }


    //扩展名提取
    private static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1);
    }

    //三级文件名提取
    private static String extractFileName(Document doc) {
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
