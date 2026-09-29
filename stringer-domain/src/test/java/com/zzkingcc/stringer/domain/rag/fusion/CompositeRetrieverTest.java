package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.RetrievalScoreKeys;
import com.zzkingcc.stringer.domain.rag.retriever.CompositeRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 {@link CompositeRetriever} 只做编排与失败处理，融合委托给 {@link FusionStrategy}。
 */
class CompositeRetrieverTest {

    private static Content c(String text, float raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RetrievalScoreKeys.RAW_SCORE, (double) raw);
        return Content.from(TextSegment.from(text, Metadata.from(m)));
    }

    private static final ContentRetriever VEC = query -> List.of(c("v", 0.9f));
    private static final ContentRetriever KW = query -> List.of(c("k", 0.8f));

    @Test
    void delegatesToStrategy_andMergesTwoChannels() {
        CompositeRetriever r = new CompositeRetriever(VEC, KW, FusionConfig.defaults(), null, 0L, null);
        List<Content> out = r.retrieve(new Query("q"));
        // 两路各 1 条不同内容 -> 去重后 2 条
        assertEquals(2, out.size());
    }

    @Test
    void oneChannelFails_stillReturnsFromOther() {
        ContentRetriever failing = query -> {
            throw new RuntimeException("boom");
        };
        CompositeRetriever r = new CompositeRetriever(VEC, failing);
        List<Content> out = r.retrieve(new Query("q"));
        assertEquals(1, out.size());
        assertEquals("vector", out.get(0).textSegment().metadata().getString(RetrievalScoreKeys.MATCH_CHANNEL));
    }

    @Test
    void bothChannelsFail_throws() {
        ContentRetriever failing = query -> {
            throw new RuntimeException("boom");
        };
        CompositeRetriever r = new CompositeRetriever(failing, failing);
        assertThrows(IllegalStateException.class, () -> r.retrieve(new Query("q")));
    }

    @Test
    void customStrategy_isUsed() {
        // 一个只返回固定 1 条的假策略，验证构造器注入生效
        FusionStrategy fixed = (queryText, v, k, fusion) -> List.of(c("injected", 0.5f));
        CompositeRetriever r = new CompositeRetriever(VEC, KW, FusionConfig.defaults(), null, 0L, fixed);
        List<Content> out = r.retrieve(new Query("q"));
        assertEquals(1, out.size());
        assertEquals("injected", out.get(0).textSegment().text());
    }
}
