package com.quanxiaoha.vector.store.runner;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时把演示文档写入 Redis 向量库。
 * <p>
 * 这里必须给每条文档指定**固定的 id**：RedisVectorStore 是用 {@code prefix + document.getId()}
 * 作为 Redis key 做 JSON.SET 覆盖写的，id 固定就意味着每次启动都是同一条 key 的覆盖更新，
 * 不会像随机 UUID 那样每次启动都往库里追加一份重复数据。
 *
 * @program: xiaoha-vector-store-search
 * @ClassName InitEmbeddingIndexRunner
 * @Author CFY
 * @create: 2026-11-22 19:24
 */
@Component
@ConditionalOnProperty(prefix = "app.embedding-index", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class InitEmbeddingIndexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitEmbeddingIndexRunner.class);

    /**
     * 种子文档 id 前缀。带上前缀是为了和 /read/ingest 进来的文档区分开，
     * 便于识别来源、也便于将来做清理。
     */
    private static final String ID_PREFIX = "seed:";

    @Resource
    private VectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        // 固定 id => 重复执行时是覆盖写，不会产生重复数据
        List<Document> documents = List.of(
                Document.builder()
                        .id(ID_PREFIX + "fridge-manual")
                        .text("冰箱首次使用指南：拆箱后静置24小时再通电，调节温控器至4℃冷藏室、-18℃冷冻室。清洁内胆时请使用中性洗涤剂。")
                        .metadata(Map.of("章节", "安全须知", "版本", "2025", "sourceType", "seed"))
                        .build(),
                Document.builder()
                        .id(ID_PREFIX + "fridge-energy-tips")
                        .text("节能技巧：避免频繁开关门，热食冷却后再放入，定期除霜可提升制冷效率。建议每月清洁一次冷凝器。")
                        .metadata(Map.of("sourceType", "seed"))
                        .build(),
                Document.builder()
                        .id(ID_PREFIX + "fridge-fault-codes")
                        .text("故障代码手册：E1温度传感器异常，E2化霜故障，F1通讯错误。遇到E2代码请断电2小时后重启，若持续出现需联系售后。")
                        .metadata(Map.of("章节", "故障处理", "紧急程度", "高", "sourceType", "seed"))
                        .build()
        );

        // id 固定，Redis 底层是 JSON.SET 覆盖写，重复启动不会堆积重复数据
        vectorStore.add(documents);

        log.info("演示文档初始化完成，共 {} 条（id 前缀 {}），重复启动会覆盖写同一批 key", documents.size(), ID_PREFIX);
    }

}
