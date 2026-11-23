package com.quanxiaoha.vector.store.service;

import com.quanxiaoha.vector.store.model.SearchResult;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 联网搜索问答服务：把「搜索 → 抓网页 → 拼上下文 → 大模型作答」串成一条完整链路。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public interface NetworkSearchAssistantService {

    /**
     * 搜索并抓取网页正文。
     *
     * @param question 用户问题
     * @return 带网页正文的搜索结果列表，按相关性评分从高到低
     */
    List<SearchResult> searchPages(String question);

    /**
     * 拼装完整的增强提示词（模板渲染后的结果）。
     *
     * @param question 用户问题
     * @param pages   带网页正文的搜索结果
     * @return 渲染后的提示词
     */
    String buildPrompt(String question, List<SearchResult> pages);

    /**
     * 联网搜索问答（非流式）。
     *
     * @param question 用户问题
     * @return 大模型基于网页内容给出的回答
     */
    String ask(String question);

    /**
     * 联网搜索问答（流式）。
     *
     * @param question 用户问题
     * @return 逐段返回的大模型回答
     */
    Flux<String> askStream(String question);

}
