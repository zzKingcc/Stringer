package com.zzkingcc.stringer.domain.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一期语义：记忆<b>只增不淘汰</b>，到上限由<b>入口</b>拒绝新一轮（会话作废、换 sessionId）。
 *
 * <p>上限只约束"能不能开新一轮"，不约束"能不能收尾"——最终回答永远允许写入，
 * 否则会留下有问无答的孤立提问。</p>
 */
class DualConstraintChatMemoryTest {

    /** 内存版 store（一期记忆只增不减，一个 List 就够） */
    private static final class InMemoryStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new LinkedHashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(memoryId, messages == null ? new ArrayList<>() : new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }
    }

    private static DualConstraintChatMemory memory(int maxMessages, int maxTokens) {
        return new DualConstraintChatMemory("default|s1", maxMessages, maxTokens, new InMemoryStore());
    }

    /** 40 个汉字 ≈ 60 tokens（CJK 按 1.5/字估算），便于精确构造超限场景 */
    private static final String LONG_TEXT = "甲".repeat(40);

    @Test
    void 超过条数上限也不再淘汰旧消息() {
        DualConstraintChatMemory memory = memory(4, 100_000);

        for (int i = 0; i < 6; i++) {
            memory.add(UserMessage.from("第" + i + "问"));
        }

        assertEquals(6, memory.messages().size(), "一期不再淘汰：旧消息必须原样留着");
        assertEquals("第0问", ((UserMessage) memory.messages().get(0)).singleText(), "最旧的那条不能被挤掉");
    }

    @Test
    void 空记忆可以开新一轮() {
        assertTrue(memory(100, 30_000).capacityFor("你好").canAccept());
    }

    @Test
    void 还能塞下整轮时放行_塞不下整轮就拒绝() {
        DualConstraintChatMemory memory = memory(4, 100_000);

        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));

        // 2 条 + 本轮 2 条 = 4 ≤ 上限 → 放行
        assertTrue(memory.capacityFor("你好").canAccept());

        memory.add(UserMessage.from("再问"));
        memory.add(AiMessage.from("再答"));

        // 4 条 + 本轮 2 条 = 6 > 上限 → 拒绝
        DualConstraintChatMemory.Capacity capacity = memory.capacityFor("你好");
        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("更换 sessionId"), "拒绝原因必须带上可操作的动作：" + capacity.detail());
    }

    @Test
    void 拒绝是粘性的_满了之后每次都拒绝() {
        DualConstraintChatMemory memory = memory(2, 100_000);
        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));

        DualConstraintChatMemory.Capacity first = memory.capacityFor("你好");
        DualConstraintChatMemory.Capacity second = memory.capacityFor("你好");

        assertFalse(first.canAccept());
        assertFalse(second.canAccept(), "记忆只增不减 → 判定天然粘性，不需要额外记'已封顶'标记位");
        assertEquals(first.detail(), second.detail());
    }

    @Test
    void token用完也拒绝() {
        DualConstraintChatMemory memory = memory(1000, 30);
        memory.add(UserMessage.from(LONG_TEXT));   // 60 + 4 = 64 tokens，已超上限

        DualConstraintChatMemory.Capacity capacity = memory.capacityFor("你好");

        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("tokens"), capacity.detail());
    }

    @Test
    void 单条提问本身超上限时给出专门文案() {
        DualConstraintChatMemory memory = memory(1000, 10);

        DualConstraintChatMemory.Capacity capacity = memory.capacityFor(LONG_TEXT);

        assertFalse(capacity.canAccept());
        assertTrue(capacity.detail().contains("单条提问"), capacity.detail());
    }

    @Test
    void 出口不受上限约束_最终回答永远能写入() {
        DualConstraintChatMemory memory = memory(2, 30);
        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));
        assertFalse(memory.capacityFor("你好").canAccept(), "入口已拒绝");

        memory.add(AiMessage.from(LONG_TEXT));

        assertEquals(3, memory.messages().size(), "最终回答必须写得进去，否则会留下有问无答的孤立提问");
    }

    @Test
    void 上限放行时detail为空() {
        assertNull(memory(100, 30_000).capacityFor("你好").detail());
    }
}
