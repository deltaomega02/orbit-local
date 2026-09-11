package com.orbit.ai.gemini

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.ai.ClothingAnalyzer
import com.orbit.ai.OutfitRecommender
import com.orbit.ai.TryOnImageGenerator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 실제 Gemini 어댑터 등록. `orbit.gemini.enabled=false`(테스트 설정)면 빈을 만들지 않아 실제 API 를 부르지 않는다.
 * 빈이 있어도 키가 비어 있으면 [GeminiClient.requireUsable] 이 503 을 던진다.
 */
@Configuration
@EnableConfigurationProperties(GeminiProperties::class)
@ConditionalOnProperty(prefix = "orbit.gemini", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class GeminiConfig {

    @Bean
    fun geminiClient(properties: GeminiProperties, keyStore: GeminiKeyStore) =
        GeminiClient(properties, keyStore)

    @Bean
    fun clothingAnalyzer(
        client: GeminiClient,
        properties: GeminiProperties,
        objectMapper: ObjectMapper,
    ): ClothingAnalyzer = GeminiClothingAnalyzer(client, properties, objectMapper)

    @Bean
    fun outfitRecommender(
        client: GeminiClient,
        properties: GeminiProperties,
        objectMapper: ObjectMapper,
    ): OutfitRecommender = GeminiOutfitRecommender(client, properties, objectMapper)

    @Bean
    fun tryOnImageGenerator(
        client: GeminiClient,
        properties: GeminiProperties,
    ): TryOnImageGenerator = GeminiTryOnImageGenerator(client, properties)
}
