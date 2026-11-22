package com.quanxiaoha.vector.store.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 增强检索：先查向量库拿到资料，再让大模型基于资料作答。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@RestController
@RequestMapping("/rag")
@Tag(name = "3. RAG 增强检索", description = "先检索向量库拿到资料，再让大模型基于资料作答（Retrieval-Augmented Generation）")
public class RAGController {

    /**
     * 检索时取回多少条资料作为上下文。
     */
    @Value("${app.rag.top-k:5}")
    private int topK;

    /**
     * 相似度阈值，低于该值的文档不进入上下文，避免无关资料干扰模型。
     */
    @Value("${app.rag.similarity-threshold:0.0}")
    private double similarityThreshold;

    /**
     * 严格约束模型只依据检索到的资料作答的系统提示词。
     */
    private static final String SYSTEM_PROMPT = """
            你是一名严谨的知识库问答助手。
            请仅依据【上下文信息】回答用户问题，不要使用你自己的先验知识，也不要编造。
            如果上下文信息中没有相关内容，请直接回答「未找到相关资料」。""";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RAGController(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    /**
     * 流式对话。
     *
     * @param message 用户问题
     * @return 逐段返回的模型回答
     */
    @GetMapping(value = "/generateStream", produces = "text/html;charset=utf-8")
    @Operation(summary = "RAG 流式问答", description = "检索向量库后让大模型基于资料流式作答")
    public Flux<String> generateStream(
            @Parameter(description = "用户问题", example = "整机主要部件保修几年？")
            @RequestParam(value = "message") String message) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message) // 用户问题
                .advisors(questionAnswerAdvisor()) // 先检索向量库，组合增强提示词，再调用大模型
                .stream()
                .content();
    }

    /**
     * 非流式对话，方便直接调用和调试。
     *
     * @param message 用户问题
     * @return 模型回答
     */
    @GetMapping(value = "/generate")
    @Operation(summary = "RAG 非流式问答", description = "检索向量库后让大模型基于资料作答，一次性返回完整回答")
    public String generate(
            @Parameter(description = "用户问题", example = "整机主要部件保修几年？")
            @RequestParam(value = "message") String message) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .advisors(questionAnswerAdvisor())
                .call()
                .content();
    }

    /**
     * 非流式对话（POST + JSON body）。
     * 中文问题放 URL 里既难读又容易碰到长度限制，走 JSON body 更稳妥。
     *
     * @param request 请求体
     * @return 模型回答
     */
    @PostMapping(value = "/generate")
    @Operation(summary = "RAG 非流式问答（POST）", description = "同 GET 版本，但问题走 JSON body，适合中文长问题")
    public String generate(@RequestBody RagRequest request) {
        return generate(request.message());
    }

    /**
     * 流式对话（POST + JSON body）。
     *
     * @param request 请求体
     * @return 逐段返回的模型回答
     */
    @PostMapping(value = "/generateStream", produces = "text/html;charset=utf-8")
    @Operation(summary = "RAG 流式问答（POST）", description = "同 GET 版本，但问题走 JSON body")
    public Flux<String> generateStreamPost(@RequestBody RagRequest request) {
        return generateStream(request.message());
    }

    private QuestionAnswerAdvisor questionAnswerAdvisor() {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(topK) // 检索条数
                        .similarityThreshold(similarityThreshold) // 相似度阈值
                        .build())
                .build();
    }

    /**
     * RAG 请求体。
     *
     * @param message 用户问题
     */
    public record RagRequest(String message) {
    }

}
