package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.reader.MyHtmlReader;
import com.quanxiaoha.vector.store.reader.MyJsonReader;
import com.quanxiaoha.vector.store.reader.MyMarkdownReader;
import com.quanxiaoha.vector.store.reader.MyPdfReader;
import com.quanxiaoha.vector.store.reader.MyTikaPptReader;
import com.quanxiaoha.vector.store.reader.MyTikaWordReader;
import com.quanxiaoha.vector.store.reader.MyTextReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档批量加载与入库服务。
 * <p>
 * 启动任务（{@code InitEmbeddingIndexRunner}）和 {@code /read/ingest} 接口共用这里的逻辑，
 * 保证两条路径产出的 Document 完全一致 —— 也就是落到同一批 Redis key 上，
 * 重复执行只是覆盖写，不会产生重复数据。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Service
public class DocumentIngestService {

    /**
     * 支持的格式与别名。保持插入顺序，报错提示里的可选值列表才稳定可读。
     */
    private static final Map<String, String> TYPE_ALIASES = new LinkedHashMap<>();

    static {
        TYPE_ALIASES.put("txt", "txt");
        TYPE_ALIASES.put("json", "json");
        TYPE_ALIASES.put("md", "markdown");
        TYPE_ALIASES.put("markdown", "markdown");
        TYPE_ALIASES.put("html", "html");
        TYPE_ALIASES.put("pdf", "pdf");
        TYPE_ALIASES.put("word", "word");
        TYPE_ALIASES.put("docx", "word");
        TYPE_ALIASES.put("ppt", "ppt");
        TYPE_ALIASES.put("pptx", "ppt");
    }

    private final MyTextReader textReader;
    private final MyJsonReader jsonReader;
    private final MyMarkdownReader markdownReader;
    private final MyHtmlReader htmlReader;
    private final MyPdfReader pdfReader;
    private final MyTikaWordReader tikaWordReader;
    private final MyTikaPptReader tikaPptReader;
    private final VectorStore vectorStore;

    public DocumentIngestService(MyTextReader textReader, MyJsonReader jsonReader,
                                 MyMarkdownReader markdownReader, MyHtmlReader htmlReader, MyPdfReader pdfReader,
                                 MyTikaWordReader tikaWordReader, MyTikaPptReader tikaPptReader,
                                 VectorStore vectorStore) {
        this.textReader = textReader;
        this.jsonReader = jsonReader;
        this.markdownReader = markdownReader;
        this.htmlReader = htmlReader;
        this.pdfReader = pdfReader;
        this.tikaWordReader = tikaWordReader;
        this.tikaPptReader = tikaPptReader;
        this.vectorStore = vectorStore;
    }

    /**
     * 按格式读取文档，不写入向量库。
     *
     * @param type txt / json / md / html / pdf / word / ppt / all，多个用逗号分隔
     * @return 格式名 -&gt; 文档集合
     */
    public Map<String, List<Document>> load(String type) {
        Map<String, List<Document>> loaded = new LinkedHashMap<>();
        for (String requested : type.split(",")) {
            String key = requested.trim();
            if (key.isEmpty()) {
                continue;
            }
            if ("all".equalsIgnoreCase(key)) {
                loadAll(loaded);
                continue;
            }
            String canonical = TYPE_ALIASES.get(key.toLowerCase());
            if (canonical == null) {
                throw new IllegalArgumentException(
                        "不支持的文档类型: " + key + "，可选值: " + String.join("/", TYPE_ALIASES.keySet()) + "/all");
            }
            loaded.put(canonical, loadOne(canonical));
        }
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException("没有指定任何文档类型，可选值: " + String.join("/", TYPE_ALIASES.keySet()) + "/all");
        }
        return loaded;
    }

    /**
     * 按格式读取文档并向量化写入 Redis 向量库。
     *
     * @param type txt / json / md / html / pdf / word / ppt / all，多个用逗号分隔
     * @return 各格式写入的条数
     */
    public Map<String, Integer> ingest(String type) {
        Map<String, List<Document>> loaded = load(type);
        Map<String, Integer> result = new LinkedHashMap<>();
        loaded.forEach((key, documents) -> {
            // sourceType 由各 Reader 自己写入，这里不再覆盖
            vectorStore.add(documents);
            result.put(key, documents.size());
        });
        return result;
    }

    private void loadAll(Map<String, List<Document>> loaded) {
        loaded.put("txt", loadOne("txt"));
        loaded.put("json", loadOne("json"));
        loaded.put("markdown", loadOne("markdown"));
        loaded.put("html", loadOne("html"));
        loaded.put("pdf", loadOne("pdf"));
        loaded.put("word", loadOne("word"));
        loaded.put("ppt", loadOne("ppt"));
    }

    private List<Document> loadOne(String canonicalType) {
        return switch (canonicalType) {
            case "txt" -> textReader.loadTextAndSplit(); // 长文本入库前先分块，否则单个超大向量会稀释语义
            case "json" -> jsonReader.loadJson();
            case "markdown" -> markdownReader.loadMarkdown();
            case "html" -> htmlReader.loadHtml();
            case "pdf" -> pdfReader.getDocsFromPdf();
            case "word" -> tikaWordReader.load();
            case "ppt" -> tikaPptReader.load();
            default -> throw new IllegalArgumentException("不支持的文档类型: " + canonicalType);
        };
    }

}
