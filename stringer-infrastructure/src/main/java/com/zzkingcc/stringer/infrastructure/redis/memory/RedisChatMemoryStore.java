package com.zzkingcc.stringer.infrastructure.redis.memory;

import com.zzkingcc.stringer.common.exception.ChatMemoryException;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.support.SessionKeys;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的会话记忆持久化存储
 * @author zzkingcc
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "stringer:chat:memory:";

    /** SCAN 每批拉取的键数（大批量扫描时的单次往返规模） */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    /**
     * @param redisTemplate Redis 模板
     * @param ttl           记忆保留期；{@code null} = <b>永久不过期</b>（一期默认：记忆是长期存储，不是缓存）
     */
    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            String json = redisTemplate.opsForValue().get(key);

            if (json == null || json.isBlank()) {
                log.debug("[会话记忆] 读取会话[{}]：无历史消息", memoryId);
                return new ArrayList<>();
            }

            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            log.debug("[会话记忆] 读取会话[{}]：{} 条历史消息", memoryId, messages.size());
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_READ_ERROR, "读取会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            if (messages == null || messages.isEmpty()) {
                redisTemplate.delete(key);
                log.debug("[会话记忆] 会话[{}]消息列表为空，已清除旧记录", memoryId);
                return;
            }

            String json = ChatMessageSerializer.messagesToJson(messages);
            redisTemplate.opsForValue().set(key, json);

            if (ttl != null && !ttl.isZero()) {
                redisTemplate.expire(key, ttl);
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL={} 分钟",
                        memoryId, messages.size(), ttl.toMinutes());
            } else {
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL=永久",
                        memoryId, messages.size());
            }
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 更新会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_WRITE_ERROR, "更新会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            redisTemplate.delete(key);
            log.debug("[会话记忆] 删除会话[{}]记忆", memoryId);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 删除会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "删除会话记忆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除某个<b>域</b>下的全部会话记忆（删域级联清理用）。
     *
     * <p>键形如 {@code stringer:chat:memory:{域}|{sessionId}}，因此按 {@code 域|} 前缀扫描即可。
     * 记忆保留期默认是<b>永久</b>，不清掉的话：同路径域将来重建，这段对话历史会原样复活 ——
     * 用户以为域已删干净，实际上一句旧话就把它勾回来了。</p>
     *
     * <p>用 SCAN 而非 KEYS：KEYS 会阻塞整个 Redis 实例。</p>
     *
     * @return 删除的键数
     */
    public int deleteByDomain(String domain) {
        return deleteByPattern(KEY_PREFIX + normalizedDomain(domain) + SessionKeys.SEPARATOR + "*", "会话记忆");
    }

    /**
     * 按模式删除本存储负责的键。失败只告警不抛 —— 删域流程不应因清理失败而中止
     * （域本身的删除已经完成，这里是收尾动作）。
     */
    private int deleteByPattern(String pattern, String what) {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        try {
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) {
                return 0;
            }
            Long deleted = redisTemplate.delete(keys);
            log.info("[{}] 已按域清理 {} 个键（模式 {}）", what, deleted, pattern);
            return deleted == null ? 0 : deleted.intValue();
        } catch (Exception e) {
            log.warn("[{}] 按模式清理失败（不阻断删域流程）: {}：{}", what, pattern, e.getMessage(), e);
            return 0;
        }
    }

    private static String normalizedDomain(String domain) {
        return domain == null ? "" : domain.trim();
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

}
