package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.common.exception.KnowledgeBaseException;
import com.zzkingcc.stringer.infrastructure.ingestion.IngestDocument;

import java.util.List;
import java.util.Locale;

/**
 * PDF 文档处理策略（{@code .pdf}）——<b>尚未落地</b>。
 *
 * <p>PDF 的难点不在"辨认二进制"，而在它的结构：<b>PDF 里只有「文字 + 坐标 + 字号」，没有行、段、标题</b>。
 * 需要先自己抽行（拿坐标与字号）、删页眉页脚（页眉里带页码，得先把数字归一化才好比对重复）、
 * 拼跨页段落（实测页码边界几乎总是断在半句上），标题还得靠字号推断 ——
 * 这部分工作量约等于 md + docx 之和，单独排一步。</p>
 *
 * <p>这里先占住扩展名，让"传了 pdf 上来"得到一句明确的答复，
 * 而不是静默跳过、最后报一句"没写出任何片段"。</p>
 *
 * @author zzkingcc
 */
public class PdfDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> PDF_EXTENSIONS = List.of("pdf");

    @Override
    protected SplitResult splitDocuments(List<IngestDocument> documents) {
        throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                "当前版本尚不支持 pdf —— 抽行与页眉页脚清洗还没落地，请转成 txt / md / docx 后再上传");
    }

    /** pdf 是二进制：入口<b>不解码</b>，原始字节交给未来的 PDFBox 抽取 */
    @Override
    public boolean binary() {
        return true;
    }

    @Override
    public List<String> supportedExtensions() {
        return PDF_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "PDF类型";
    }

    /**
     * 判断给定扩展名是否为 PDF 类型
     */
    public static boolean isPdfExtension(String ext) {
        if (ext == null || ext.isBlank()) return false;
        return PDF_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
