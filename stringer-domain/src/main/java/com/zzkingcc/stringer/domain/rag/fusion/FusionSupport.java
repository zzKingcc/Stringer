package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.rag.content.Content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 融合策略共用的辅助函数（去重哈希、取分、标题 / 文件名命中判定、命中通道命名）。
 *
 * <p>抽出来是为了让分数制（{@link DefaultFusionStrategy}）与排名制（{@link RrfFusionStrategy}）
 * 共用同一套判定，避免两处各写一份而慢慢走偏。</p>
 *
 * @author zzkingcc
 */
final class FusionSupport {

    private FusionSupport() {
    }

    /** 按文本内容 SHA-256 判同一条 */
    static String hashContent(Content content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.textSegment().text().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return content.textSegment().text();
        }
    }

    /** 从 Content metadata 中提取 ES 检索分数 */
    static double extractScore(Content content) {
        try {
            return content.textSegment().metadata().getFloat(RetrievalScoreKeys.RAW_SCORE);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 命中通道命名：vector / keyword / both */
    static String channel(boolean vectorHit, boolean keywordHit) {
        if (vectorHit && keywordHit) {
            return "both";
        }
        return vectorHit ? "vector" : "keyword";
    }

    /**
     * 切片序号（同一文档内的顺序）。
     *
     * <p>只用于<b>同分时的稳定排序</b>：融合分完全相同时按原文顺序排，
     * 让结果可复现（否则 ES 返回顺序抖动会让同一问题两次答案不一致）。</p>
     */
    static int chunkSeq(Content content) {
        try {
            Integer seq = content.textSegment().metadata().getInteger("chunk_seq");
            return seq == null ? Integer.MAX_VALUE : seq;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 标题是否命中查询词 */
    static boolean titleHit(Content content, String query) {
        String title = meta(content, "section_title");
        return title != null && !title.isBlank() && containsAnyKeyword(title, query);
    }

    /** 文件名是否命中查询词 */
    static boolean fileNameHit(Content content, String query) {
        String fileName = meta(content, "file_name");
        return fileName != null && !fileName.isBlank() && containsAnyKeyword(fileName, query);
    }

    private static String meta(Content content, String key) {
        try {
            return content.textSegment().metadata().getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断文本中是否包含查询词的任意关键词（中文按单字/词匹配，英文按空格分词） */
    static boolean containsAnyKeyword(String text, String query) {
        if (text == null || query == null) {
            return false;
        }
        String lowerText = text.toLowerCase();
        for (String kw : extractKeywords(query)) {
            if (kw.length() >= 2 && lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> extractKeywords(String query) {
        return Arrays.stream(query.toLowerCase()
                        .split("[\\s，。！？、；：\"'（）《》\\[\\]【】,.!?;:()]+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
