package com.orbit

import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.media.MediaStorage
import com.orbit.repository.ClothesRepository
import com.orbit.repository.CoordinationRepository
import com.orbit.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이미지 업로드와 서빙.
 * 올린 사람만 볼 수 있고, 이미지가 아닌 파일은 들어오지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("미디어 — 업로드·소유권 서빙·형식/크기 검증")
class MediaApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var mediaStorage: MediaStorage

    private lateinit var api: TestApiClient
    private lateinit var me: Session
    private lateinit var other: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("media-me@orbit.test")
        other = api.signUpAndLogin("media-other@orbit.test")
    }

    private fun uploadClothes(
        session: Session,
        bytes: ByteArray = pngBytes(),
        contentType: String? = "image/png",
        name: String = "사진 있는 셔츠",
    ) = mockMvc.perform(
        multipart("/api/clothes")
            .file(MockMultipartFile("image", "shirt.png", contentType, bytes))
            .param("name", name)
            .param("mainCategory", "TOP")
            .header(HttpHeaders.AUTHORIZATION, session.bearer),
    )

    @Test
    fun `사진과 함께 등록하면 imageUrl 이 생기고 그 URL 로 원본을 다시 받을 수 있다`() {
        val bytes = pngBytes()
        val body = uploadClothes(me, bytes)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.imageUrl").isString)
            .andReturn().response.contentAsString

        val imageUrl = api.json(body)["imageUrl"] as String
        // 한 디렉토리에 파일이 쌓이지 않도록 날짜별로 나눈다
        assertTrue(Regex("^/media/clothes/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png$").matches(imageUrl), imageUrl)

        val served = mockMvc.perform(get(imageUrl).header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andReturn().response

        assertContentEquals(bytes, served.contentAsByteArray)
        assertTrue(served.contentType!!.startsWith("image/png"), served.contentType!!)
    }

    /** UUID 경로는 추측하기 어려울 뿐 접근 제어가 아니다. */
    @Test
    fun `남의 이미지 경로를 알아도 조회할 수 없다`() {
        val body = uploadClothes(other, name = "남의 셔츠").andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val othersUrl = api.json(body)["imageUrl"] as String

        mockMvc.perform(get(othersUrl).header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isNotFound)

        // 주인은 볼 수 있어야 위 404 가 전부 막은 결과가 아님을 알 수 있다
        mockMvc.perform(get(othersUrl).header(HttpHeaders.AUTHORIZATION, other.bearer))
            .andExpect(status().isOk)
    }

    @Test
    fun `인증 없이 미디어에 접근하면 401`() {
        val body = uploadClothes(me).andReturn().response.contentAsString
        val imageUrl = api.json(body)["imageUrl"] as String

        mockMvc.perform(get(imageUrl)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `이미지가 아닌 파일은 415 로 거절한다`() {
        mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "evil.png", "image/png", "이건 이미지가 아니다".toByteArray()))
                .param("name", "가짜")
                .param("mainCategory", "TOP")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_image_type"))
    }

    /** Content-Type 은 클라이언트가 보내는 값이라 매직 바이트와 어긋나면 믿지 않는다. */
    @Test
    fun `선언한 형식과 실제 바이트가 다르면 415 로 거절한다`() {
        mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "shirt.gif", "image/gif", pngBytes()))
                .param("name", "형식 불일치")
                .param("mainCategory", "TOP")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_image_type"))
    }

    /** 크기 상한은 없다. 디스크는 저장 직전 축소(긴 변 1600px)가 지킨다. */
    @Test
    fun `큰 이미지도 거절하지 않고 등록된다`() {
        val oversized = pngBytes() + ByteArray(5_000_000)

        mockMvc.perform(
            multipart("/api/clothes")
                .file(MockMultipartFile("image", "huge.png", "image/png", oversized))
                .param("name", "아주 큰 사진")
                .param("mainCategory", "TOP")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.imageUrl").isNotEmpty)
    }

    @Test
    fun `사진 없이도 등록되고 imageUrl 은 null 이다`() {
        mockMvc.perform(
            multipart("/api/clothes")
                .param("name", "사진 없는 셔츠")
                .param("mainCategory", "TOP")
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.imageUrl").isEmpty)
    }

    @Test
    fun `전신 사진을 올리면 내 정보에서 URL 로 보인다`() {
        val body = mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/users/me/body-photo")
                .file(MockMultipartFile("image", "body.png", "image/png", pngBytes()))
                .header(HttpHeaders.AUTHORIZATION, me.bearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bodyPhotoUrl").isString)
            .andReturn().response.contentAsString

        val url = api.json(body)["bodyPhotoUrl"] as String
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, me.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bodyPhotoUrl").value(url))
            .andExpect(jsonPath("$.email").value(me.email))

        // 다른 사람에게는 보이지 않는다
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, other.bearer))
            .andExpect(status().isNotFound)
    }

    /** 서빙 경로에는 임의 문자열이 들어오므로 루트 밖을 가리키는 경로는 저장소가 끊는다. */
    @Test
    fun `상대 경로로 미디어 루트 밖을 읽을 수 없다`() {
        assertNull(mediaStorage.read("../../../../etc/passwd"))
        assertNull(mediaStorage.read("clothes/../../../etc/hosts"))
    }
}
