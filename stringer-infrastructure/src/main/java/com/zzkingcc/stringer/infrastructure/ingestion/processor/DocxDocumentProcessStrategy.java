package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import com.zzkingcc.stringer.infrastructure.ingestion.IngestDocument;
import com.zzkingcc.stringer.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.stringer.infrastructure.ingestion.block.BlockSplitter;
import com.zzkingcc.stringer.infrastructure.ingestion.docx.DocxReader;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Word（{@code .docx}）文档处理策略。
 *
 * <p>格式适配用 POI 直读：段落样式拿标题层级、{@code getBodyElements()} 保证表格与段落按原文顺序穿插、
 * 页眉页脚压根不读。之后的打包与去重交给通用的 {@link BlockSplitter}。</p>
 *
 * <p><b>旧版 {@code .doc} 不支持</b>：HWPF 拿不到段落样式（没有标题层级），解析出来接近乱文本，
 * 入库只会拖低整体检索质量。它的扩展名不在这里注册。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class DocxDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> DOCX_EXTENSIONS = List.of("docx");

    @Override
    protected SplitResult splitDocuments(List<IngestDocument> documents) {
        BlockSplitter splitter = BlockChunking.newSplitter();
        List<TextSegment> all = new ArrayList<>();
        int titles = 0;
        int dropped = 0;
        for (IngestDocument source : documents) {
            byte[] raw = source.content();
            if (raw == null || raw.length == 0) {
                log.warn("[docx切片] 文件[{}]内容为空，跳过", safeFileName(source));
                continue;
            }
            DocxReader.Result parsed = DocxReader.read(raw);
            if (parsed.blocks().isEmpty()) {
                // 整篇只有图片的 docx 是典型的"扫描件"变体 —— 明确报错，不要"导入成功、0 片"
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "文件 " + safeFileName(source) + " 没有解析出任何文字内容"
                                + "（可能整篇是图片 / 截图，或文档被加密），请提供文字版");
            }
            SplitResult one = splitter.splitWithStats(source, parsed.blocks(), parsed.dropped());
            all.addAll(one.segments());
            titles += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, titles, dropped);
    }

    /** docx 是二进制：入口<b>不解码</b> */
    @Override
    public boolean binary() {
        return true;
    }

    @Override
    public List<String> supportedExtensions() {
        return DOCX_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Word类型";
    }
}
