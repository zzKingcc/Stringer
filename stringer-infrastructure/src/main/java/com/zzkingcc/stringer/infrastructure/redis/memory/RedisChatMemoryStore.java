package com.zzkingcc.stringer.infrastructure.redis.memory;

import com.zzkingcc.stringer.common.exception.ChatMemoryException;
import com.zzkingcc.stringer.api.code.ErrorCode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 会话记忆过期时间：null 表示永久不过期 */
    private static final Duration DEFAULT_TTL = null;

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_TTL);
    }

    /**
     * @param redisTemplate Redis 模板
     * @param ttl           会话记忆过期时间，null 表示不过期
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

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

}
