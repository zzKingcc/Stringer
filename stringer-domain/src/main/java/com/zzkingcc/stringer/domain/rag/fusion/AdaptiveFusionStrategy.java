package com.zzkingcc.stringer.domain.rag.fusion;

import com.zzkingcc.stringer.domain.rag.model.RankedList;
import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 双轨融合策略：按<b>本次召回涉及的索引数</b>自动选择算法。
 *
 * <ul>
 *   <li><b>单索引</b>（来源数 ≤ 1）→ {@link DefaultFusionStrategy}（分数制），与升级前行为一致；</li>
 *   <li><b>多索引</b>（来源数 ≥ 2）→ {@link RrfFusionStrategy}（排名制），规避 BM25 跨索引不可比。</li>
 * </ul>
 *
 * <p>这是本项目的默认融合入口 —— 双轨的意义在于：一个域只挂了自身索引时，
 * 结果与旧版逐字节一致；一旦沿父类链取到多个索引，自动切到免疫量纲问题的 RRF。
 * 将来若要统一为 RRF，只需把本类替换掉即可，召回编排无需改动。</p>
 *
 * @author zzkingcc
 */
public class AdaptiveFusionStrategy implements FusionStrategy {

    private final FusionStrategy singleTrack;
    private final FusionStrategy multiTrack;

    public AdaptiveFusionStrategy() {
        this(new DefaultFusionStrategy(), new RrfFusionStrategy());
    }

    public AdaptiveFusionStrategy(FusionStrategy singleTrack, FusionStrategy multiTrack) {
        this.singleTrack = singleTrack;
        this.multiTrack = multiTrack;
    }

    @Override
    public List<Content> fuse(String queryText, List<RankedList> lists, FusionConfig fusion) {
        long sources = lists == null ? 0 : lists.stream()
                .map(RankedList::sourceId)
                .distinct()
                .count();
        FusionStrategy picked = sources >= 2 ? multiTrack : singleTrack;
        return picked.fuse(queryText, lists, fusion);
    }
}
