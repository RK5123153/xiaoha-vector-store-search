package com.quanxiaoha.vector.store.runner;

import com.quanxiaoha.vector.store.service.DocumentIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时把文档批量向量化存储到 Redis 向量库。
 * <p>
 * 这里的重点是**幂等**：各 Reader 产出的 Document id 是确定性的（由来源文件 + 定位信息推导），
 * RedisVectorStore 的 key = {@code prefix + document.getId()} 且底层是 JSON.SET 覆盖写，
 * 所以每次启动都是对同一批 key 的更新，不会像随机 UUID 那样越堆越多。
 * <p>
 * 加载逻辑与 {@code /read/ingest} 接口共用 {@link DocumentIngestService}，
 * 两条路径产出完全一致的 key，不会出现「同一份文档被塞两遍」。
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
     * 启动时灌库最多尝试几次。
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 两次尝试之间的间隔（毫秒）。
     */
    private static final long RETRY_DELAY_MILLIS = 3000L;

    /**
     * 启动时灌入哪些格式：txt/json/md/html/pdf/word/ppt/all，多个用逗号分隔。
     */
    @Value("${app.embedding-index.sources:all}")
    private String sources;

    private final DocumentIngestService documentIngestService;

    public InitEmbeddingIndexRunner(DocumentIngestService documentIngestService) {
        this.documentIngestService = documentIngestService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 读取文档 -> 向量化 -> 写入 Redis；id 固定 => 覆盖写，重复启动不会产生重复数据
        //
        // 这里刻意不让异常冒出去：灌库要调用外部的 embedding 模型（百炼 API），
        // 一次 DNS 抖动或接口超时就把整个应用拖死并不划算 ——
        // 种子数据只是「锦上添花」，真正要用时还能调 POST /read/ingest?type=all 手动补灌。
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Integer> result = documentIngestService.ingest(sources);
                int total = result.values().stream().mapToInt(Integer::intValue).sum();
                log.info("文档初始化完成，来源 [{}]，共写入 {} 条（{}），重复启动会覆盖写同一批 key",
                        sources, total, result);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("## 文档初始化第 {}/{} 次失败：{}", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(RETRY_DELAY_MILLIS);
                }
            }
        }
        log.error("## 文档初始化最终失败，向量库里可能没有种子数据；"
                + "可在服务启动后手动调用 POST /read/ingest?type=all 补灌。根因：{}",
                lastError == null ? "未知" : lastError.getMessage(), lastError);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}

