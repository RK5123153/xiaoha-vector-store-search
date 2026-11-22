package com.quanxiaoha.vector.store.runner;

import com.quanxiaoha.vector.store.reader.MyPdfReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把 PDF 文档向量化存储到 Redis 向量库。
 * <p>
 * 这里的重点是**幂等**：文档 id 是由「文件名 + 页码」推导出来的固定值，
 * RedisVectorStore 的 key = {@code prefix + document.getId()} 且底层是 JSON.SET 覆盖写，
 * 所以每次启动都是对同一批 key 的更新，不会像随机 UUID 那样越堆越多。
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

    private final MyPdfReader pdfReader;
    private final VectorStore vectorStore;

    public InitEmbeddingIndexRunner(MyPdfReader pdfReader, VectorStore vectorStore) {
        this.pdfReader = pdfReader;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 读取 PDF 并转换为 Document 文档集合（每页一条，id 固定）
        List<Document> documents = pdfReader.getDocsFromPdf();

        // 通过向量模型，将文档向量化存储到 Redis；id 固定 => 覆盖写，重复启动不会产生重复数据
        vectorStore.add(documents);

        log.info("PDF 文档初始化完成，共 {} 条，重复启动会覆盖写同一批 key", documents.size());
    }

}
