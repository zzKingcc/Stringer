package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import com.zzkingcc.stringer.infrastructure.ingestion.txt.TxtChunking;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 纯文本类型文档处理策略（只认 {@code .txt}）。
 *
 * <p>分片交给 {@code TxtSplitter}（统一字符集 → 清洗 → 认标题 → 按句子切片），
 * 去重与向量化流程由 {@link AbstractDocumentProcessStrategy} 统一封装。</p>
 *
 * <p>md 走 {@code MarkdownDocumentProcessStrategy}（md 有原生结构，#{@code #} 前缀是确定信息，
 * 不该退化成"按纯文本猜标题"）；docx / pdf 将来按同一接口新增策略，工厂与现有策略都不动。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class TextDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    /** 适用类型：本轮只支持纯文本 */
    public static final List<String> TEXT_EXTENSIONS = List.of("txt");

    @Override
    protected SplitResult splitDocuments(List<Document> documents) {
        return TxtChunking.newSplitter().splitAllWithStats(documents);
    }

    @Override
    public List<String> supportedExtensions() {
        return TEXT_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "纯文本类型";
    }

    /**
     * 判断给定扩展名是否为纯文本类型（工厂调用用）
     */
    public static boolean isTextExtension(String ext) {
        if (ext == null || ext.isBlank()) return false;
        return TEXT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
