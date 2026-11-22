package com.quanxiaoha.vector.store.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * @program: xiaoha-vector-store-search
 * @ClassName VectorSearchController
 * @Author CFY
 * @create: 2026-11-22 20:36
 */
@RestController
@RequestMapping("/vector")
@Tag(name = "2. 向量检索", description = "在 Redis 向量库中做语义检索，可按来源格式过滤")
public class VectorSearchController {

    private final VectorStore vectorStore;

    public VectorSearchController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 检索向量数据库
     *
     * @param key        查询关键词
     * @param topK       返回条数，默认 2
     * @param sourceType 可选，按来源格式过滤，如 txt / json / markdown / html / pdf / word / ppt
     * @return 命中的文档列表
     */
    @GetMapping(value = "/search")
    @Operation(summary = "语义检索", description = "按语义相似度返回最相关的文档，结果按分数倒序")
    public List<Document> search(
            @Parameter(description = "查询关键词", example = "整机保修期是多久")
            @RequestParam(value = "key") String key,
            @Parameter(description = "返回条数", example = "5")
            @RequestParam(value = "topK", defaultValue = "2") int topK,
            @Parameter(description = "按来源格式过滤，留空不过滤", example = "pdf")
            @RequestParam(value = "sourceType", required = false) String sourceType) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(key) // 查询的关键词
                .topK(topK); // 返回相似度最高的 N 条文档

        if (sourceType != null && !sourceType.isBlank()) {
            // sourceType 已在 RedisVectorStoreConfig 中声明为可索引的 TEXT 元数据字段
            builder.filterExpression("sourceType == '%s'".formatted(sourceType));
        }

        // Spring AI 2.0 的 RedisVectorStore 拼出来的 KNN 查询是 `%s=>[KNN %s @%s $%s AS %s]`，
        // 没有 SORTBY，而且 doSimilaritySearch 也不做排序，
        // 所以返回顺序是 RediSearch 的内部顺序而不是相似度顺序 —— 这里按分数倒序排一次。
        return vectorStore.similaritySearch(builder.build()).stream()
                .sorted(Comparator.comparing((Document doc) -> doc.getScore() == null
                        ? Double.NEGATIVE_INFINITY : doc.getScore()).reversed())
                .toList();
    }

}
