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
 * 실제 Gemini 어댑터를 등록하는 유일한 지점.
 *
 * `orbit.gemini.enabled=false` 면 빈이 아예 만들어지지 않는다. 테스트 설정이
 * 이 값을 false 로 두므로, **테스트 컨텍스트에는 네트워크를 타는 구현이 존재조차
 * 하지 않는다.** 실수로 실제 API 를 부르는 사고가 구조적으로 불가능하다.
 * (프로파일 대신 설정값을 쓴 이유: 운영에서도 AI 만 잠시 끄고 싶을 때가 있는데,
 *  그걸 위해 프로파일을 갈아끼우는 건 과하다)
 *
 * 빈이 있어도 키가 비어 있으면 [GeminiClient.requireUsable] 이 503 을 던진다.
 * 즉 "AI 를 못 쓰는 상태"는 옷장 CRUD 를 전혀 건드리지 않는다 — 원본 Django 가
 * 잘 해둔 판단이라 그대로 가져왔다.
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
