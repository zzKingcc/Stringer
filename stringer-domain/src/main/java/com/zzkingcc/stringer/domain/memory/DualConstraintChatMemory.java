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
 * 双约束会话记忆（消息条数 + Token 估算）
 *
 * <p><b>一期语义：只增不淘汰。</b>记忆是长期存储（Redis + RDB/AOF），不会为了塞下新消息而丢弃最旧的
 * —— 静默丢历史会让用户"以为还记得"。到上限后改由<b>入口</b>拒绝新一轮：该会话作废，调用方必须换
 * {@code sessionId} 重新开始（判定见 {@link #capacityFor(String)}）。</p>
 *
 * <p>上限只约束"能不能开新一轮"，不约束"能不能收尾"：最终回答永远允许写入，否则会留下有问无答的
 * 孤立提问。因此一轮结束后总量可能略微超过上限（回答长度不可预估）。</p>
 *
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
        // 纯追加：不淘汰、不判上限。上限由入口的 capacityFor(...) 把关，
        // 出口（最终回答）永远允许写入。
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);
        store.updateMessages(id, messages);
    }

    /**
     * 入口容量判定：把"本轮提问"算进去，判断这个会话还能不能再开一轮。
     *
     * <p>判定是<b>粘性</b>的：记忆只增不减，所以一旦满了，之后每次判定都会失败 ——
     * 不需要额外记"已封顶"标记位。（唯一能解除的是 Redis 数据丢失，所以 RDB+AOF 是这套语义的前提。）</p>
     *
     * <p>条数按<b>整轮预留</b>（提问 + 回答）判定，让"100 条"成为真正的硬上限；Token 只按提问判定
     * （回答长度不可预估），因此允许一轮结束后轻度超出。</p>
     */
    public Capacity capacityFor(String pendingQuestion) {
        List<ChatMessage> messages = store.getMessages(id);
        int currentTokens = estimateTokens(messages);
        int questionTokens = estimateTokens(pendingQuestion) + PER_MESSAGE_OVERHEAD;

        if (questionTokens > maxTokens) {
            return Capacity.full("单条提问已超过会话记忆上限（约 " + questionTokens + " tokens > 上限 "
                    + maxTokens + "），请缩短内容，或更换 sessionId 开启新会话");
        }
        if (messages.size() + 2 > maxMessages) {
            return Capacity.full("会话记忆已达上限（当前 " + messages.size() + " 条 + 本轮 2 条 > 上限 "
                    + maxMessages + " 条 ≈ " + (maxMessages / 2) + " 轮问答），请更换 sessionId 开启新会话");
        }
        if (currentTokens + questionTokens > maxTokens) {
            return Capacity.full("会话记忆已达上限（当前约 " + currentTokens + " tokens + 本轮提问约 "
                    + questionTokens + " > 上限 " + maxTokens + " tokens），请更换 sessionId 开启新会话");
        }
        return Capacity.ok();
    }

    /** 入口容量判定结果；{@code canAccept=false} 时 {@code detail} 是可直接回给调用方的拒绝原因 */
    public record Capacity(boolean canAccept, String detail) {

        static Capacity ok() {
            return new Capacity(true, null);
        }

        static Capacity full(String detail) {
            return new Capacity(false, detail);
        }
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
