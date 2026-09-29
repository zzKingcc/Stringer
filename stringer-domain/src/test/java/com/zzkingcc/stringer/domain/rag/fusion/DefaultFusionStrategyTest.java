package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住默认融合算法：去重 / 归一化加权 / TopN 重排 / 标题文件名 boost / 通道标记。
 * 这些是升级前 {@code CompositeRetriever} 的内联行为，抽成 {@link DefaultFusionStrategy} 后
 * 必须逐字节等价 —— 本测试即"对拍"。
 */
class DefaultFusionStrategyTest {

    private static Content content(String text, float rawScore, String title, String fileName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        if (title != null) {
            m.put("section_title", title);
        }
        if (fileName != null) {
            m.put("file_name", fileName);
        }
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    @Test
    void emptyChannels_returnEmpty() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        assertTrue(s.fuse("q", List.of(), List.of(), FusionConfig.defaults()).isEmpty());
    }

    @Test
    void dedupAcrossChannels_keepsBothScoresAndMarksBoth() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content shared = content("same text", 0.9f, "退款", "a.md");
        // 同一条内容出现在两路 -> 去重为 1 条，但两个通道分都记录
        List<Content> result = s.fuse("退款", List.of(shared), List.of(shared), FusionConfig.defaults());
        assertEquals(1, result.size());
        Metadata m = result.get(0).textSegment().metadata();
        assertEquals("both", m.getString(RetrievalScoreKeys.MATCH_CHANNEL));
        assertTrue(m.toMap().containsKey(RetrievalScoreKeys.VECTOR_SCORE));
        assertTrue(m.toMap().containsKey(RetrievalScoreKeys.KEYWORD_SCORE));
        assertEquals(1, m.getInteger(RetrievalScoreKeys.FUSION_RANK));
    }

    @Test
    void topN_truncatesAndRanksByFusedScoreDesc() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content c1 = content("A", 0.9f, null, null);
        Content c2 = content("B", 0.5f, null, null);
        Content c3 = content("C", 0.1f, null, null);
        // 向量权重 1.0、无 boost、TopN=2
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.0, 0.0, 2);
        List<Content> result = s.fuse("q", List.of(c1, c2, c3), List.of(), cfg);
        assertEquals(2, result.size());
        double first = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double second = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(first >= second, "融合分应按降序");
        assertEquals(1, result.get(0).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
        assertEquals(2, result.get(1).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
    }

    @Test
    void titleMatch_getsBoostAndRanksFirst() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        // 两路原始分相同；一条标题命中查询词 -> 应加 boost 并排第一
        Content withTitle = content("X", 0.5f, "退款政策", "a.md");
        Content withoutTitle = content("Y", 0.5f, "其他说明", "b.md");
        FusionConfig cfg = new FusionConfig(1.0, 0.0, 0.2, 0.0, 10);
        List<Content> result = s.fuse("退款", List.of(withTitle, withoutTitle), List.of(), cfg);
        assertEquals(2, result.size());
        assertEquals("X", result.get(0).textSegment().text());
        double boosted = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double plain = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(boosted > plain, "标题命中应获得更高融合分");
    }

    @Test
    void differentChannels_distinctEntries() {
        DefaultFusionStrategy s = new DefaultFusionStrategy();
        Content v = content("vec-only", 0.7f, null, null);
        Content k = content("kw-only", 0.6f, null, null);
        List<Content> result = s.fuse("q", List.of(v), List.of(k), FusionConfig.defaults());
        assertEquals(2, result.size());
        Map<String, Object> meta = result.stream()
                .filter(c -> "vec-only".equals(c.textSegment().text()))
                .findFirst().orElseThrow().textSegment().metadata().toMap();
        assertEquals("vector", meta.get(RetrievalScoreKeys.MATCH_CHANNEL));
    }
}
