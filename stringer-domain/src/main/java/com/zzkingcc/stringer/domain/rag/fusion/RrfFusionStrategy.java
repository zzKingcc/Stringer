package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.Modality;
import com.zzkingcc.stringer.domain.rag.model.RankedList;
import com.zzkingcc.stringer.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * RRF（Reciprocal Rank Fusion）融合策略 —— <b>多索引</b>召回时使用。
 *
 * <p>为什么多索引必须换它：BM25 的 idf 用<b>本索引（本分片）</b>的文档频率计算，
 * 同一个词在不同索引里量纲不同 —— 把多索引的 BM25 分池化后做 min-max 只会把
 * 「小索引里稀有词被抬高的分」当成 max，把其余结果压扁。向量分虽可比，但同一轮里
 * 两路量纲也不同。RRF 只看<b>名次</b>，天然免疫这一切。</p>
 *
 * <p>公式：{@code score(d) = Σ_L (w_L / (k + rank_L(d)))}，k 默认 60。
 * <b>权重按模态均摊</b>：每张向量表的 {@code w = vectorWeight / 向量表个数}，
 * 每张关键词表 {@code w = keywordWeight / 关键词表个数} —— 这样 向量:关键词 的比例
 * 不随域链长度（索引个数）漂移，否则祖先越多、向量表越多，BM25 会被越压越扁。</p>
 *
 * @author zzkingcc
 */
public class RrfFusionStrategy implements FusionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RrfFusionStrategy.class);

    /** 单张排名表最多计入的名次，防长尾把常量 k 的平滑效果冲淡 */
    private static final int MAX_RANK = 200;

    @Override
    public List<Content> fuse(String queryText,
                              List<RankedList> lists,
                              FusionConfig fusion) {
        long start = System.currentTimeMillis();

        List<RankedList> all = lists == null ? List.of() : lists;
        long vectorLists = all.stream().filter(l -> l != null && l.modality() == Modality.VECTOR).count();
        long keywordLists = all.stream().filter(l -> l != null && l.modality() == Modality.KEYWORD).count();
        int k = Math.max(fusion.rrfK(), 1);

        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();
        for (RankedList list : all) {
            if (list == null || list.contents().isEmpty()) {
                continue;
            }
            boolean vector = list.modality() == Modality.VECTOR;
            long count = vector ? vectorLists : keywordLists;
            double perList = count <= 0 ? 0.0
                    : (vector ? fusion.vectorWeight() : fusion.keywordWeight()) / count;

            int rank = 0;
            for (Content c : list.contents()) {
                rank++;
                if (rank > MAX_RANK) {
                    break;
                }
                String hash = FusionSupport.hashContent(c);
                ScoreEntry entry = scoreMap.computeIfAbsent(hash, x -> new ScoreEntry(c, queryText, fusion));
                entry.rrf += perList / (k + rank);
                double raw = FusionSupport.extractScore(c);
                if (vector) {
                    entry.vectorScore = entry.vectorScore == null ? raw : Math.max(entry.vectorScore, raw);
                } else {
                    entry.keywordScore = entry.keywordScore == null ? raw : Math.max(entry.keywordScore, raw);
                }
            }
        }

        if (scoreMap.isEmpty()) {
            log.info("[RRF融合] 无命中结果,耗时 {}ms", System.currentTimeMillis() - start);
            return new ArrayList<>();
        }

        List<ScoreEntry> entries = new ArrayList<>(scoreMap.values());
        for (ScoreEntry e : entries) {
            double score = e.rrf;
            String title = e.content.textSegment().metadata().getString("section_title");
            if (title != null && !title.isBlank() && FusionSupport.containsAnyKeyword(title, e.queryText)) {
                score += e.fusion.titleBoost();
            }
            String fileName = e.content.textSegment().metadata().getString("file_name");
            if (fileName != null && !fileName.isBlank() && FusionSupport.containsAnyKeyword(fileName, e.queryText)) {
                score += e.fusion.fileNameBoost();
            }
            e.fusedScore = score;
        }

        entries.sort(Comparator.comparingDouble(e -> -e.fusedScore));
        int topN = Math.min(Math.max(fusion.topN(), 1), entries.size());

        List<Content> result = IntStream.range(0, topN)
                .mapToObj(i -> withScores(entries.get(i), i + 1))
                .collect(Collectors.toList());

        log.info("[RRF融合] 表数={}(向量{} / 关键词{}),去重后{}条,融合重排Top{},耗时{}ms",
                all.size(), vectorLists, keywordLists, scoreMap.size(), topN,
                System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 把分项分、融合分、名次、命中通道回写到 metadata
     */
    private Content withScores(ScoreEntry e, int rank) {
        TextSegment source = e.content.textSegment();
        Map<String, Object> meta = new LinkedHashMap<>(source.metadata().toMap());

        if (e.vectorScore != null) {
            meta.put(RetrievalScoreKeys.VECTOR_SCORE, e.vectorScore);
        }
        if (e.keywordScore != null) {
            meta.put(RetrievalScoreKeys.KEYWORD_SCORE, e.keywordScore);
        }
        meta.put(RetrievalScoreKeys.FUSED_SCORE, e.fusedScore);
        meta.put(RetrievalScoreKeys.FUSION_RANK, rank);
        meta.put(RetrievalScoreKeys.MATCH_CHANNEL,
                FusionSupport.channel(e.vectorScore != null, e.keywordScore != null));

        return Content.from(TextSegment.from(source.text(), Metadata.from(meta)));
    }

    /** 单条结果的 RRF 累计分记录 */
    private static class ScoreEntry {
        final Content content;
        final String queryText;
        final FusionConfig fusion;
        Double vectorScore;
        Double keywordScore;
        double rrf;
        double fusedScore;

        ScoreEntry(Content content, String queryText, FusionConfig fusion) {
            this.content = content;
            this.queryText = queryText;
            this.fusion = fusion;
        }
    }
}
