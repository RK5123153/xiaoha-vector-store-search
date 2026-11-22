package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Txt 文件读取器。
 * <p>
 * Spring AI 2.0 的 {@link TextReader} 只提供 {@code get()}，不再有 1.0.x 的 {@code read()}。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyTextReader {

    private static final String FILENAME = "manual.txt";

    /**
     * 来源标题，去掉扩展名。会拼进正文参与向量计算。
     */
    private static final String TITLE = "海尔BCD-520WDPD对开门冰箱使用说明书";

    private final Resource resource;

    public MyTextReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    /**
     * 读取整个 Txt 文件，不做任何切分，整个文本作为一条 Document 返回。
     */
    public List<Document> loadText() {
        TextReader textReader = new TextReader(resource);
        // 添加自定义元数据，如文件名称
        textReader.getCustomMetadata().put("filename", FILENAME);
        textReader.getCustomMetadata().put("sourceType", "txt");

        List<Document> documents = textReader.get();
        List<Document> result = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            result.add(Documents.withTitle(
                    Documents.withId(documents.get(i), "txt:" + FILENAME, Map.of()), TITLE));
        }
        return result;
    }

    /**
     * 读取 Txt 文件并分块拆分，适用于体积较大的文本。
     */
    public List<Document> loadTextAndSplit() {
        TextReader textReader = new TextReader(resource);
        textReader.getCustomMetadata().put("filename", FILENAME);
        textReader.getCustomMetadata().put("sourceType", "txt");

        List<Document> documents = textReader.get();

        // 使用 TokenTextSplitter 对文档列表进行分块处理
        List<Document> splitDocuments = TokenTextSplitter.builder()
                .withChunkSize(50) // 每个分块的目标 token 数
                .withMinChunkSizeChars(20) // 标点符号截断阶段的字符下限
                .withMinChunkLengthToEmbed(10) // 允许入库（embedding）的最小字符长度，小于此长度的块会被直接丢弃
                .withMaxNumChunks(5000) // 单个文档最多生成的块数，超出部分被截断，防止超长文档导致块爆炸
                .withKeepSeparator(true) // 是否在分块边界保留分隔符（标点符号）
                .build()
                .apply(documents);

        // 固定 id（用分块序号）+ 带上来源标题，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(splitDocuments.size());
        for (int i = 0; i < splitDocuments.size(); i++) {
            Document doc = splitDocuments.get(i);
            Object chunkIndex = doc.getMetadata().get("chunk_index");
            String id = "txt:" + FILENAME + ":c" + (chunkIndex != null ? chunkIndex : i);
            result.add(Documents.withTitle(Documents.withId(doc, id, Map.of()), TITLE));
        }
        return result;
    }

}
