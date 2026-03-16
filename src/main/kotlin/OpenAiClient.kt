package org.example

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams

private val MODEL = ChatModel.GPT_5_1_CODEX

class OpenAiClient(apiKey: String) {

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    fun chat(messages: List<ChatMessage>): String {
        val builder = ChatCompletionCreateParams.builder()
            .model(MODEL)
            .maxCompletionTokens(2000L)

        for (msg in messages) {
            when (msg.role) {
                "system"    -> builder.addSystemMessage(msg.content)
                "assistant" -> builder.addAssistantMessage(msg.content)
                else        -> builder.addUserMessage(msg.content)
            }
        }

        val completion = client.chat().completions().create(builder.build())
        return completion.choices().firstOrNull()
            ?.message()
            ?.content()
            ?.orElse("") ?: ""
    }
}