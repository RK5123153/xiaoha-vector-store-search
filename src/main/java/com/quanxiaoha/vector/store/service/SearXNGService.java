package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.model.SearchResult;

import java.util.List;

/**
 * 联网搜索服务：调用本机（或局域网）部署的 SearXNG，拿聚合去重后的搜索结果。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public interface SearXNGService {

    /**
     * 调用 SearXNG API 获取搜索结果，返回条数由配置 {@code app.searxng.count} 决定。
     *
     * @param query 搜索关键词
     * @return 搜索结果列表，按相关性评分从高到低
     */
    List<SearchResult> search(String query);

    /**
     * 调用 SearXNG API 获取搜索结果，并限制返回条数。
     *
     * @param query 搜索关键词
     * @param limit 最多返回多少条，传 null 表示用配置里的条数；实际条数不会超过配置的上限
     * @return 搜索结果列表，按相关性评分从高到低
     */
    List<SearchResult> search(String query, Integer limit);

}
