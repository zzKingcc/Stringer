package com.zzkingcc.stringer.domain.rag.model;

import dev.langchain4j.rag.content.Content;

import java.util.List;

/**
 * 一份<b>排名表</b>：某个来源（索引）上某一路模态的召回结果，已按该路自己的分数降序排列。
 *
 * <p>多索引召回时，每个「索引 × 模态」通道产出一份排名表。融合策略据此：
 * <ul>
 *   <li>分数制（{@code DefaultFusionStrategy}）—— 按模态把多表拼回两路，再归一化加权；</li>
 *   <li>排名制（{@code RrfFusionStrategy}）—— 只看各表内的名次，天然免疫跨索引分数量纲不一致。</li>
 * </ul>
 *
 * @param sourceId 来源标识（本项目中即索引名）
 * @param modality 模态
 * @param contents 该来源该模态的召回结果，按分数降序
 *
 * @author zzkingcc
 */
public record RankedList(String sourceId, Modality modality, List<Content> contents) {

    public RankedList {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }
}
