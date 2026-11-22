package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF 文件读取器。
 * <p>
 * {@link PagePdfDocumentReader} 依赖 PDFBox 抽取文字，{@code pagesPerDocument} 决定几页合成一条 Document。
 * <p>
 * <b>一个 key 就是一个向量</b>：RedisVectorStore 的 Redis key = {@code prefix + document.getId()}，
 * 一条 Document 只对应一个向量。所以「一份 PDF 一个 key」等价于「整份 PDF 只生成一个向量」，
 * 检索时只能定位到整份文档，无法定位到具体某一页。
 * <ul>
 *   <li>{@code pages-per-document = 1}：每页一条 Document，每页一个 key —— 检索能精确定位到页</li>
 *   <li>{@code pages-per-document = 0}（{@code PdfDocumentReaderConfig.ALL_PAGES}）：整份 PDF 合成一条
 *       Document，一个 key —— key 少，但长 PDF 的向量语义会被稀释</li>
 * </ul>
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyPdfReader {

    private static final String FILENAME = "BelieveInYou.pdf";

    /**
     * 来源标题，去掉扩展名。会拼进正文参与向量计算。
     */
    private static final String TITLE = "BelieveInYou";

    private static final String FILE_PATH = "classpath:/document/" + FILENAME;

    /**
     * 每个 Document 覆盖几页。0 表示整份 PDF 合成一条 Document（一个 key）。
     */
    @Value("${app.reader.pdf.pages-per-document:1}")
    private int pagesPerDocument;

    /**
     * 读取 PDF 文件。
     */
    public List<Document> getDocsFromPdf() {
        // 新建 PagePdfDocumentReader 阅读器
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(FILE_PATH,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0) // 设置页面顶边距为 0
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0) // 设置删除顶部文本行数为 0
                                .withLeftAlignment(true) // 去掉每行左侧的空白，PDF 是绝对定位排版，不去掉会带大量空格噪声
                                .build())
                        .withPagesPerDocument(pagesPerDocument) // 每 N 页合成一条 Document，0 = 整份合成一条
                        .build());

        // 读取并转换为 Document 文档集合
        List<Document> documents = pdfReader.read();

        // 固定 id + 带上来源标题，重复入库是覆盖写而不是追加
        List<Document> result = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            // 合并模式下一条 Document 覆盖多页，page_number 是起始页、end_page_number 是结束页
            int startPage = intMeta(doc, "page_number", 1);
            int endPage = intMeta(doc, "end_page_number", startPage);
            int pages = endPage - startPage + 1;

            // 整份合成一条时用文件级 id；每页一条时带上页码
            String id = pagesPerDocument == PdfDocumentReaderConfig.ALL_PAGES
                    ? "pdf:" + FILENAME
                    : "pdf:" + FILENAME + ":p" + startPage;

            result.add(Documents.withTitle(
                    Documents.withId(doc, id, Map.of(
                            "sourceType", "pdf",
                            "filename", FILENAME,
                            "pages", pages)),
                    TITLE));
        }
        return result;
    }

    private int intMeta(Document doc, String key, int defaultValue) {
        Object value = doc.getMetadata().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

}
