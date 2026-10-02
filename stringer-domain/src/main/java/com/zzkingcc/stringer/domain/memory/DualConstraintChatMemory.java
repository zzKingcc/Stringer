package com.zzkingcc.stringer.domain.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 双约束会话记忆
 * 1、消息条数，上线50伦
 * 2、Token 数，30k
 * @author zzkingcc
 */
public class DualConstraintChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(DualConstraintChatMemory.class);

    private final Object id;
    private final int maxMessages;
    private final int maxTokens;
    private final ChatMemoryStore store;

    /**
     * @param id          会话 ID
     * @param maxMessages 消息条数上限
     * @param maxTokens   Token 估算上限
     * @param store       持久化存储（如 RedisChatMemoryStore）
     */
    public DualConstraintChatMemory(Object id, int maxMessages, int maxTokens, ChatMemoryStore store) {
        this.id = id;
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.store = store;
    }

    @Override
    public Object id() {
        return id;
    }

    /** 每条消息的结构开销 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);

        // 增量维护 token 总量:淘汰是"从最旧删除",只需减去被删消息的量,避免平方复杂度重算
        int totalTokens = estimateTokens(messages);
        int evictedCount = 0;

        while (messages.size() > 1
                && (messages.size() > maxMessages || totalTokens > maxTokens)) {
            ChatMessage removed = messages.remove(0);
            totalTokens -= estimateTokens(extractText(removed)) + PER_MESSAGE_OVERHEAD;
            evictedCount++;
        }

        if (evictedCount > 0) {
            log.debug("[会话记忆] 会话[{}] 淘汰 {} 条旧消息（当前 {} 条，约 {} tokens）",
                    id, evictedCount, messages.size(), Math.max(totalTokens, 0));
        }

        store.updateMessages(id, messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 把记忆整体替换为给定内容(任务停止时回滚用)
     *
     * @param messages 目标内容;null 视为清空
     */
    public void restore(List<ChatMessage> messages) {
        List<ChatMessage> target = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        store.updateMessages(id, target);
        log.info("[会话记忆] 会话[{}] 记忆已恢复为 {} 条", id, target.size());
    }

    /**
     * 估算消息列表的总 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokens(extractText(msg));
        }
        // 每条消息额外计入 PER_MESSAGE_OVERHEAD token 的结构开销（role 标记等）
        total += messages.size() * PER_MESSAGE_OVERHEAD;
        return total;
    }

    /**
     * 估算单段文本的 Token 数（字符级启发式）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                tokens += 1.5;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }

    /**
     * 判断字符是否为 CJK 字符（中文、日文、韩文、全角符号）
     */
    private boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')   // CJK 统一表意文字
                || (c >= '\u3400' && c <= '\u4DBF') // CJK 扩展 A
                || (c >= '\u3000' && c <= '\u303F') // CJK 符号和标点
                || (c >= '\uFF00' && c <= '\uFFEF') // 全角字符
                || (c >= '\uAC00' && c <= '\uD7AF'); // 韩文音节
    }

    /**
     * 从消息中提取纯文本内容
     */
    private String extractText(ChatMessage message) {
        if (message instanceof SystemMessage sm) {
            return sm.text();
        } else if (message instanceof AiMessage am) {
            return am.text() != null ? am.text() : "";
        } else if (message instanceof UserMessage um) {
            try {
                return um.singleText();
            } catch (Exception e) {
                return um.toString();
            }
        } else if (message instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
