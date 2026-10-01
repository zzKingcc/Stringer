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
 * 默认融合策略（<b>分数制</b>）—— 复刻升级前的两路融合算法，单索引场景使用：
 *
 * <ul>
 *   <li>把多份排名表按模态拼回两路（向量 / 关键词），保持各表内部顺序；</li>
 *   <li>去重：按文本内容 SHA-256 判同一条；两路都命中只记一次，但两个通道分都保留</li>
 *   <li>归一化：向量分 / 关键词分各自做 min-max（单路缺失按 0 算）</li>
 *   <li>加权：{@code 向量权重×归一化向量分 + 关键词权重×归一化关键词分}，标题 / 文件名命中再各加 boost</li>
 *   <li>重排：按融合分降序，取 TopN，回写分项分 / 融合分 / 名次 / 命中通道</li>
 * </ul>
 *
 * <p>单索引（1 张向量表 + 1 张关键词表）时与升级前逐字节一致。多索引场景由
 * {@link RrfFusionStrategy} 承担 —— 因为 BM25 分跨索引不可比，分数制在这里会失真。</p>
 *
 * @author zzkingcc
 */
public class DefaultFusionStrategy implements FusionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultFusionStrategy.class);

    @Override
    public List<Content> fuse(String queryText,
                             List<RankedList> lists,
                             FusionConfig fusion) {
        long start = System.currentTimeMillis();

        List<Content> vector = new ArrayList<>();
        List<Content> keyword = new ArrayList<>();
        if (lists != null) {
            for (RankedList l : lists) {
                if (l == null) {
                    continue;
                }
                if (l.modality() == Modality.VECTOR) {
                    vector.addAll(l.contents());
                } else if (l.modality() == Modality.KEYWORD) {
                    keyword.addAll(l.contents());
                }
            }
        }

        // 合并去重 + 记录分数来源
        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();

        for (Content c : vector) {
            String hash = FusionSupport.hashContent(c);
            double score = FusionSupport.extractScore(c);
            scoreMap.computeIfAbsent(hash, k -> new ScoreEntry(c, queryText, fusion)).vectorScore = score;
        }

        int keywordAdded = 0;
        for (Content c : keyword) {
            String hash = FusionSupport.hashContent(c);
            double score = FusionSupport.extractScore(c);
            ScoreEntry exist = scoreMap.get(hash);
            if (exist != null) {
                exist.keywordScore = score;
            } else {
                ScoreEntry entry = new ScoreEntry(c, queryText, fusion);
                entry.keywordScore = score;
                scoreMap.put(hash, entry);
                keywordAdded++;
            }
        }

        if (scoreMap.isEmpty()) {
            log.info("[融合] 无命中结果,耗时 {}ms", System.currentTimeMillis() - start);
            return new ArrayList<>();
        }

        List<ScoreEntry> entries = new ArrayList<>(scoreMap.values());
        normalize(entries);
        computeFusedScores(entries);

        entries.sort(Comparator.comparingDouble(e -> -e.fusedScore));
        int topN = Math.min(Math.max(fusion.topN(), 1), entries.size());

        List<Content> result = IntStream.range(0, topN)
                .mapToObj(i -> withScores(entries.get(i), i + 1))
                .collect(Collectors.toList());

        log.info("[融合] 向量命中{}条,关键词命中{}条(新增{}条),去重后{}条,融合重排Top{},耗时{}ms",
                vector.size(), keyword.size(), keywordAdded,
                scoreMap.size(), topN, System.currentTimeMillis() - start);

        return result;
    }

    // ==================== 以下为复刻的融合算法细节 ====================

    /**
     * 对向量分数和关键词分数分别做 min-max 归一化
     */
    private void normalize(List<ScoreEntry> entries) {
        normalizeChannel(entries, true);
        normalizeChannel(entries, false);
    }

    private void normalizeChannel(List<ScoreEntry> entries, boolean vector) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (ScoreEntry e : entries) {
            Double v = vector ? e.vectorScore : e.keywordScore;
            if (v != null) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        for (ScoreEntry e : entries) {
            Double v = vector ? e.vectorScore : e.keywordScore;
            if (v == null) {
                continue;
            }
            double norm = (max == min) ? 1.0 : (v - min) / (max - min);
            if (vector) {
                e.normVectorScore = norm;
            } else {
                e.normKeywordScore = norm;
            }
        }
    }

    /**
     * 计算融合分数:加权求和 + boost
     */
    private void computeFusedScores(List<ScoreEntry> entries) {
        for (ScoreEntry e : entries) {
            double fused = e.fusion.vectorWeight() * (e.normVectorScore != null ? e.normVectorScore : 0.0)
                    + e.fusion.keywordWeight() * (e.normKeywordScore != null ? e.normKeywordScore : 0.0);

            String title = e.content.textSegment().metadata().getString("section_title");
            if (title != null && !title.isBlank() && FusionSupport.containsAnyKeyword(title, e.queryText)) {
                fused += e.fusion.titleBoost();
            }

            String fileName = e.content.textSegment().metadata().getString("file_name");
            if (fileName != null && !fileName.isBlank() && FusionSupport.containsAnyKeyword(fileName, e.queryText)) {
                fused += e.fusion.fileNameBoost();
            }

            e.fusedScore = fused;
        }
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
        if (e.normVectorScore != null) {
            meta.put(RetrievalScoreKeys.NORM_VECTOR_SCORE, e.normVectorScore);
        }
        if (e.normKeywordScore != null) {
            meta.put(RetrievalScoreKeys.NORM_KEYWORD_SCORE, e.normKeywordScore);
        }
        meta.put(RetrievalScoreKeys.FUSED_SCORE, e.fusedScore);
        meta.put(RetrievalScoreKeys.FUSION_RANK, rank);
        meta.put(RetrievalScoreKeys.MATCH_CHANNEL,
                FusionSupport.channel(e.vectorScore != null, e.keywordScore != null));

        return Content.from(TextSegment.from(source.text(), Metadata.from(meta)));
    }

    /**
     * 单条检索结果的分数记录,用于融合计算与回写
     */
    private static class ScoreEntry {
        final Content content;
        final String queryText;
        final FusionConfig fusion;
        Double vectorScore;
        Double keywordScore;
        Double normVectorScore;
        Double normKeywordScore;
        double fusedScore;

        ScoreEntry(Content content, String queryText, FusionConfig fusion) {
            this.content = content;
            this.queryText = queryText;
            this.fusion = fusion;
        }
    }
}
