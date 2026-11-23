package com.quanxiaoha.vector.store.model;

/**
 * 一条联网搜索结果。
 * <p>
 * 注意 {@code content} 与 {@code pageContent} 的区别：
 * {@code content} 是 SearXNG 直接返回的<b>结果摘要</b>（搜索引擎给的片段，可能带「Read more」之类尾巴），
 * {@code pageContent} 才是后续小节访问 {@code url} 抓回来的<b>网页正文</b>。
 *
 * @param url           页面访问链接
 * @param title         网页标题
 * @param content       搜索结果摘要（SearXNG 返回的片段）
 * @param score         相关性评分，越大越相关
 * @param engine        命中该结果的搜索引擎
 * @param publishedDate 发布日期，没有则为空
 * @param pageContent   网页正文，后续抓取网页后填充
 * @Author: CFY
 * @create: 2026-11-23
 */
public record SearchResult(
        String url,
        String title,
        String content,
        Double score,
        String engine,
        String publishedDate,
        String pageContent) {

    /**
     * 补上网页正文，生成一份新的结果对象（record 不可变）。
     *
     * @param pageContent 网页正文
     * @return 新的 SearchResult
     */
    public SearchResult withPageContent(String pageContent) {
        return new SearchResult(url, title, content, score, engine, publishedDate, pageContent);
    }

}
