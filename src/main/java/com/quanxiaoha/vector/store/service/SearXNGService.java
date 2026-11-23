package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.model.SearchResult;

import java.util.List;
import java.util.Map;

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

    /**
     * 探测各个搜索引擎的可用状态。
     * <p>
     * 等价于 SearXNG 界面里展开的「响应时间」面板：
     * 出了结果的引擎算正常，进了 {@code unresponsive_engines} 的算失败（带失败原因）。
     * <p>
     * 注意：启用了但一条结果都没返回的引擎不会出现在结果里 —— 这属于引擎自身的问题，查不出来。
     *
     * @param query 探测用的关键词，为空时用默认词
     * @return 引擎名 -> 状态（正常 / 失败原因）
     */
    Map<String, String> probeEngines(String query);

}
