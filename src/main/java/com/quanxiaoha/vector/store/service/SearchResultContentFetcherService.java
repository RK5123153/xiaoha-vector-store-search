package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.model.SearchResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 搜索结果页面内容提取服务。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public interface SearchResultContentFetcherService {

    /**
     * 并发批量获取搜索结果页面的内容。
     * <p>
     * 每个页面一个独立任务，全部用 {@link CompletableFuture} 跑；
     * 单个页面超时或出错都不会影响其它页面，失败的页面正文保持为空。
     *
     * @param searchResults 待抓取的搜索结果
     * @param timeout       单个页面的超时时间
     * @param unit          超时时间单位
     * @return 抓取完成后的结果列表（顺序与入参一致）
     */
    CompletableFuture<List<SearchResult>> batchFetch(List<SearchResult> searchResults, long timeout, TimeUnit unit);

}
