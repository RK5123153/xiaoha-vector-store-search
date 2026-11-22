package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Apache Tika 的 Office 文档读取器基类。
 * <p>
 * Tika 通过统一 API 把 DOC/DOCX/PPT/PPTX 等格式解析成纯文本 + 元数据，
 * 这里再交给 {@link TokenTextSplitter} 切块，适合长文档入库。
 * <p>
 * 注意 Spring AI 2.0 的 {@link TikaDocumentReader} 只有 {@code get()}，
 * 1.0.x 教程里的 {@code read()} 已经不存在。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
public abstract class AbstractTikaReader {

    /**
     * 每个分块的目标 token 数。
     */
    @Value("${app.reader.tika.chunk-size:1000}")
    private int chunkSize;

    /**
     * 标点符号截断阶段的字符下限。
     */
    @Value("${app.reader.tika.min-chunk-size-chars:400}")
    private int minChunkSizeChars;

    /**
     * 允许入库（embedding）的最小字符长度，小于此长度的块会被直接丢弃。
     */
    @Value("${app.reader.tika.min-chunk-length-to-embed:10}")
    private int minChunkLengthToEmbed;

    /**
     * 单个文档最多生成的块数，超出部分被截断，防止超长文档导致块爆炸。
     */
    @Value("${app.reader.tika.max-num-chunks:5000}")
    private int maxNumChunks;

    /**
     * 待解析的资源。
     */
    protected abstract Resource resource();

    /**
     * 源文件名，用于拼 id 和元数据。
     */
    protected abstract String filename();

    /**
     * 来源格式标记，如 word / ppt。
     */
    protected abstract String sourceType();

    /**
     * 读取并切分文档。
     */
    public List<Document> load() {
        // 新建 TikaDocumentReader 阅读器
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource());
        // 读取并转换为 Document 文档集合（Spring AI 2.0 只有 get()）
        List<Document> documents = tikaDocumentReader.get();

        // 文档分块
        List<Document> splitDocuments = TokenTextSplitter.builder()
                .withChunkSize(chunkSize) // 每个分块的目标 token 数
                .withMinChunkSizeChars(minChunkSizeChars) // 标点符号截断阶段的字符下限
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed) // 允许入库的最小字符长度
                .withMaxNumChunks(maxNumChunks) // 单个文档最多生成的块数
                .withKeepSeparator(true) // 是否在分块边界保留分隔符
                .build()
                .apply(documents);

        // 固定 id，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(splitDocuments.size());
        for (int i = 0; i < splitDocuments.size(); i++) {
            Document doc = splitDocuments.get(i);
            Object chunkIndex = doc.getMetadata().get("chunk_index");
            String id = sourceType() + ":" + filename() + ":c" + (chunkIndex != null ? chunkIndex : i);
            result.add(StableId.of(doc, id, Map.of(
                    "sourceType", sourceType(),
                    "filename", filename())));
        }
        return result;
    }

}
