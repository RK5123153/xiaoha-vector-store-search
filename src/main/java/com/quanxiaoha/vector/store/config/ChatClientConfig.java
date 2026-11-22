package com.quanxiaoha.vector.store.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置。
 * <p>
 * 对话模型走 MiniMax 的 Anthropic 兼容接口（base-url = https://api.minimaxi.com/anthropic）。
 * 这里显式指定 {@link AnthropicChatModel} 而不是笼统的 ChatModel，
 * 避免和向量模型用的 OpenAI 兼容客户端产生多个候选导致注入歧义。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Configuration
public class ChatClientConfig {

    /**
     * 初始化 ChatClient 客户端。
     *
     * @param chatModel MiniMax 对话模型
     * @return ChatClient
     */
    @Bean
    public ChatClient chatClient(AnthropicChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .build();
    }

}
