package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.Modality;
import com.zzkingcc.stringer.domain.rag.model.RankedList;
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
 * 锁住排名制融合（<b>多索引</b>轨）：跨索引只看名次、按模态均摊权重、去重累计。
 */
class RrfFusionStrategyTest {

    private static Content content(String text, float rawScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) rawScore);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static RankedList list(String index, Modality modality, Content... contents) {
        return new RankedList(index, modality, List.of(contents));
    }

    @Test
    void sameContentInSeveralLists_accumulatesRankScore() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        // X 在 i1 名次 1、i2 名次 2；Y 只在 i2 名次 1
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("X", 0.9f)),
                list("i2", Modality.VECTOR, content("Y", 0.9f), content("X", 0.8f))
        ), FusionConfig.defaults());

        assertEquals(2, result.size());
        assertEquals("X", result.get(0).textSegment().text(), "多次命中累计分更高，应排第一");
        double x = result.get(0).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        double y = result.get(1).textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE);
        assertTrue(x > y);
        assertEquals(1, result.get(0).textSegment().metadata().getInteger(RetrievalScoreKeys.FUSION_RANK));
        assertEquals("vector", result.get(0).textSegment().metadata().getString(RetrievalScoreKeys.MATCH_CHANNEL));
    }

    /**
     * 原始分的量级完全不参与计算 —— 这正是多索引必须用 RRF 的理由：
     * BM25 的 idf 用本索引的文档频率，同一词在不同索引里量纲不同，分数池化后必然失真。
     */
    @Test
    void rankOrderDrivesFusion_notRawScoreMagnitude() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 10, 60);
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("small-1", 0.01f), content("small-2", 0.005f)),
                list("i2", Modality.KEYWORD, content("big-1", 19.0f), content("big-2", 9.0f))
        ), cfg);

        Map<String, Double> score = new LinkedHashMap<>();
        result.forEach(c -> score.put(c.textSegment().text(),
                c.textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE)));

        // 两表各自的名次 1 只差权重（0.6/1 与 0.4/1），与 0.01 / 19.0 的量级无关
        assertEquals(0.6 / (60 + 1), score.get("small-1"), 1e-9);
        assertEquals(0.4 / (60 + 1), score.get("big-1"), 1e-9);
        // 名次相同时按权重决胜：向量两名的分都高于关键词同名次
        assertEquals(List.of("small-1", "small-2", "big-1", "big-2"),
                result.stream().map(c -> c.textSegment().text()).toList());
    }

    /**
     * 权重按模态均摊：2 张向量表各拿 vectorWeight/2，1 张关键词表独占 keywordWeight。
     * 否则祖先域越多、向量表越多，关键词那一路会被越压越扁。
     */
    @Test
    void weightsAreSplitPerModalityTable() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 10, 60);
        // 关键词只在 i1 名次 1（权 0.4/1），向量在 i1/i2 名次 1（各 0.6/2 = 0.3）
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("V1", 0.9f)),
                list("i1", Modality.KEYWORD, content("K1", 9.0f)),
                list("i2", Modality.VECTOR, content("V2", 0.9f))
        ), cfg);

        Map<String, Double> score = new LinkedHashMap<>();
        result.forEach(c -> score.put(c.textSegment().text(),
                c.textSegment().metadata().getDouble(RetrievalScoreKeys.FUSED_SCORE)));

        double k1 = score.get("K1");
        double expectKeyword = 0.4 / (60 + 1);
        assertEquals(expectKeyword, k1, 1e-9, "关键词权重独占，不按向量表数摊薄");
        assertEquals(0.6 / 2 / (60 + 1), score.get("V1"), 1e-9, "向量权重被两张表均摊");
        assertTrue(k1 > score.get("V1"));
    }

    @Test
    void topNTruncates() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        FusionConfig cfg = new FusionConfig(0.6, 0.4, 0.0, 0.0, 1, 60);
        List<Content> result = s.fuse("q", List.of(
                list("i1", Modality.VECTOR, content("A", 0.9f), content("B", 0.5f))
        ), cfg);
        assertEquals(1, result.size());
    }

    @Test
    void emptyLists_returnEmpty() {
        RrfFusionStrategy s = new RrfFusionStrategy();
        assertTrue(s.fuse("q", List.of(), FusionConfig.defaults()).isEmpty());
        assertTrue(s.fuse("q",
                List.of(new RankedList("i1", Modality.VECTOR, List.of())),
                FusionConfig.defaults()).isEmpty());
    }
}
