package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.model.SearchResult;

import java.util.List;

/**
 * 网页正文抓取服务。
 * <p>
 * 联网搜索只拿到了链接和摘要，要让大模型真正「读到」网页，
 * 还得自己访问这些链接、把 HTML 里的正文抠出来。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public interface PageContentService {

    /**
     * 并发抓取这些页面的正文，并回填到 {@link SearchResult#pageContent()}。
     * <p>
     * 抓取失败的页面会被跳过（正文保持为 null），不影响其它页面。
     *
     * @param results 待抓取的搜索结果
     * @return 回填了正文的结果列表
     */
    List<SearchResult> fillPageContent(List<SearchResult> results);

}
