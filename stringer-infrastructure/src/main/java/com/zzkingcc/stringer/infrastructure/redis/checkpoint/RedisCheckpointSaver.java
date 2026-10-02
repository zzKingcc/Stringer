package com.zzkingcc.stringer.infrastructure.redis.checkpoint;

import com.zzkingcc.stringer.common.exception.ChatMemoryException;
import com.zzkingcc.stringer.api.code.ErrorCode;
import com.zzkingcc.stringer.api.support.SessionKeys;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.CheckpointListSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.*;
import java.time.Duration;
import java.util.*;

/**
 * 基于 Redis 的 graph 检查点持久化
 * @author zzkingcc
 */
public class RedisCheckpointSaver implements BaseCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointSaver.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "stringer:graph:checkpoint:";

    /** SCAN 每批拉取的键数（大批量扫描时的单次往返规模） */
    private static final int SCAN_BATCH = 500;

    /** 检查点默认保留时长:中断后未 resume 的会话不会主动清理,靠 TTL 兜底 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final CheckpointListSerializer checkpointListSerializer;
    private final Duration ttl;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, StateSerializer<?> stateSerializer) {
        this(redisTemplate, stateSerializer, DEFAULT_TTL);
    }

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, StateSerializer<?> stateSerializer,
                                Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.checkpointListSerializer = new CheckpointListSerializer(stateSerializer);
        this.ttl = ttl;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            // 反转:最新在前,符合 BaseCheckpointSaver 的约定(getStateHistory 取第一个为最新)
            Collections.reverse(all);
            return all;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] list 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "读取检查点列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            if (all.isEmpty()) {
                log.debug("[检查点] get 会话[{}]: 无检查点", threadId(config));
                return Optional.empty();
            }
            // 优先按 checkPointId 精确匹配
            String cpId = config.checkPointId().orElse(null);
            if (cpId != null) {
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (cpId.equals(all.get(i).getId())) {
                        return Optional.of(all.get(i));
                    }
                }
            }
            // 否则取最新
            Checkpoint latest = all.get(all.size() - 1);
            log.debug("[检查点] get 会话[{}]: 命中 checkpoint={}", threadId(config), latest.getId());
            return Optional.of(latest);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] get 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "读取检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            all.add(checkpoint);
            writeAll(key, all);
            log.info("[检查点] put 会话[{}]: 写入 checkpoint={}, node={}, next={}, 累计={}",
                    threadId(config), checkpoint.getId(), checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(), all.size());
            // 返回带新 checkPointId 的 config,resume 时据此定位
            return RunnableConfig.builder(config)
                    .checkPointId(checkpoint.getId())
                    .nextNode(checkpoint.getNextNodeId())
                    .build();
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] put 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "写入检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            Tag tag = new Tag(threadId(config), all);
            redisTemplate.delete(key);
            log.info("[检查点] release 会话[{}]: 释放 {} 个检查点", threadId(config), all.size());
            return tag;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] release 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "释放检查点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除某个<b>域</b>下的全部检查点（删域级联清理用）。
     *
     * <p>断点里存着"即将执行但还没执行"的工具调用。域被删后若不清，24 小时内同路径域重建
     * 就能 {@code resume(approved=true)} 把当初那个破坏性动作补执行掉 —— 域的删除没有撤销授权。</p>
     *
     * @return 删除的键数
     */
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        String pattern = KEY_PREFIX + normalized + SessionKeys.SEPARATOR + "*";
        if (normalized.isEmpty()) {
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
            log.info("[检查点] 已按域清理 {} 个键（模式 {}）", deleted, pattern);
            return deleted == null ? 0 : deleted.intValue();
        } catch (Exception e) {
            log.warn("[检查点] 按模式清理失败（不阻断删域流程）: {}：{}", pattern, e.getMessage(), e);
            return 0;
        }
    }

    private String buildKey(RunnableConfig config) {
        return KEY_PREFIX + threadId(config);
    }

    /**
     * 读取 threadId 下全部 checkpoint,保持写入顺序(旧→新)
     */
    private LinkedList<Checkpoint> readAll(String key) {
        String base64 = redisTemplate.opsForValue().get(key);
        if (base64 == null || base64.isBlank()) {
            return new LinkedList<>();
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return checkpointListSerializer.read(ois);
        } catch (Exception e) {
            log.warn("[检查点] 反序列化失败,当作空列表处理: {}", e.getMessage());
            return new LinkedList<>();
        }
    }

    /**
     * 写入 threadId 下全部 checkpoint
     */
    private void writeAll(String key, LinkedList<Checkpoint> all) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            checkpointListSerializer.write(all, oos);
        }
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        redisTemplate.opsForValue().set(key, base64);
        // 每次写入都续期:中断后长期不 resume 的会话不应永久占用内存
        if (ttl != null && !ttl.isZero()) {
            redisTemplate.expire(key, ttl);
        }
    }
}
