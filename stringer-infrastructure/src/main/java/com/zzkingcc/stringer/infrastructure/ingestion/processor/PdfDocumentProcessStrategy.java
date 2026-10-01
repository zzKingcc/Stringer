package com.zzkingcc.stringer.infrastructure.ingestion.processor;

import com.zzkingcc.stringer.infrastructure.ingestion.txt.TxtChunking;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF 文档处理策略（.pdf）
 *
 * <p><b>当前不可达</b>（上传白名单只有 {@code txt}，且入口按 UTF-8 解码、没有引入 Tika/PdfBox），
 * 但接口与实现都保留 —— 将来接上 PDF 抽取后，把抽取出的纯文本先过 {@link #cleanPdfText(String)}
 * 再做排版断行修复，然后交给与 txt 相同的切片器即可，不用重写一套。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class PdfDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> PDF_EXTENSIONS = List.of("pdf");

    @Override
    protected SplitResult splitDocuments(List<Document> documents) {
        List<Document> cleaned = new ArrayList<>();
        for (Document doc : documents) {
            String raw = doc.text();
            if (raw == null || raw.isBlank()) {
                log.warn("[PDF分片] 文件[{}]提取文本为空（可能是扫描版PDF），跳过", safeFileName(doc));
                continue;
            }
            String cleanedText = cleanPdfText(raw);
            if (cleanedText.isBlank()) {
                log.warn("[PDF分片] 文件[{}]清洗后文本为空，跳过", safeFileName(doc));
                continue;
            }
            cleaned.add(Document.from(cleanedText, doc.metadata()));
        }
        return TxtChunking.newSplitter().splitAllWithStats(cleaned);
    }

    /**
     * PDF 文本清洗
     *
     * <p>核心思路：按双换行（空行）切分段落，段内的单换行视为排版断行予以修复：</p>
     * <ul>
     *   <li>行尾是句末标点（。！？.!?…;；）→ 保持换行，当前行独立</li>
     *   <li>行尾非句末标点 → 判定为断行，与下一行连接（中文不加空格，英文加空格）</li>
     *   <li>连续空行压缩为单个，保留段落分隔</li>
     * </ul>
     */
    String cleanPdfText(String raw) {
        // 统一换行符
        String text = raw.replaceAll("\\r\\n?", "\n");
        // 连续 3+ 换行压缩为 2 个（保留段落分隔）
        text = text.replaceAll("\\n{3,}", "\n\n");

        String[] paragraphs = text.split("\\n{2}");
        StringBuilder result = new StringBuilder();

        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) continue;

            String[] lines = trimmedPara.split("\\n");
            StringBuilder paraBuilder = new StringBuilder();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                if (paraBuilder.length() == 0) {
                    paraBuilder.append(line);
                } else {
                    char lastChar = paraBuilder.charAt(paraBuilder.length() - 1);
                    if (isSentenceEnd(lastChar)) {
                        // 前一行以句末标点结尾，当前行另起
                        paraBuilder.append("\n").append(line);
                    } else {
                        // 断行修复：连接到当前行
                        if (isCJK(lastChar)) {
                            paraBuilder.append(line);
                        } else if (line.length() > 0 && isCJK(line.charAt(0))) {
                            paraBuilder.append(line);
                        } else {
                            paraBuilder.append(" ").append(line);
                        }
                    }
                }
            }

            if (paraBuilder.length() > 0) {
                if (result.length() > 0) result.append("\n\n");
                result.append(paraBuilder);
            }
        }

        return result.toString().trim();
    }

    /** 判断字符是否为句末标点 */
    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？'
                || c == '!' || c == '?' || c == '.'
                || c == '…' || c == ';' || c == '；';
    }

    /** 判断字符是否为 CJK 统一汉字 */
    private boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
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
