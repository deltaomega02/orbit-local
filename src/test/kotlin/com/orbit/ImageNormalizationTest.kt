package com.orbit

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.fasterxml.jackson.databind.ObjectMapper
import com.orbit.media.ImageType
import com.orbit.media.MediaProperties
import com.orbit.media.MediaStorage
import com.orbit.web.ApiExceptionHandler
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.unit.DataSize
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 큰 사진은 거절하지 않고 저장 직전에 서버가 줄인다.
 * 작은 사진은 그대로 두고, EXIF 방향은 픽셀에 적용하며, 투명도는 보존한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("이미지 정규화 — 축소·EXIF 회전·투명도 보존·안전선")
class ImageNormalizationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clothesRepository: ClothesRepository
    @Autowired lateinit var coordinationRepository: CoordinationRepository
    @Autowired lateinit var mediaStorage: MediaStorage
    @Autowired lateinit var mediaProperties: MediaProperties

    private lateinit var api: TestApiClient
    private lateinit var me: Session

    @BeforeEach
    fun setUp() {
        coordinationRepository.deleteAll()
        clothesRepository.deleteAll()
        userRepository.deleteAll()
        api = TestApiClient(mockMvc, objectMapper)
        me = api.signUpAndLogin("normalize@orbit.test")
    }

    private fun upload(
        bytes: ByteArray,
        filename: String = "photo.jpg",
        contentType: String = "image/jpeg",
    ): ResultActions = mockMvc.perform(
        multipart("/api/clothes")
            .file(MockMultipartFile("image", filename, contentType, bytes))
            .param("name", "テストの服")
            .param("mainCategory", "TOP")
            .header(HttpHeaders.AUTHORIZATION, me.bearer),
    )

    /** 업로드 응답의 imageUrl(`/media/...`)에서 저장된 바이트를 그대로 가져온다. */
    private fun storedBytesOf(result: ResultActions): ByteArray {
        val url = api.json(result.andReturn().response.contentAsString)["imageUrl"] as String
        val relative = url.removePrefix("/media/")
        return checkNotNull(mediaStorage.read(relative)) { "저장된 파일이 없다: $relative" }
    }

    private fun imageUrlOf(result: ResultActions): String =
        api.json(result.andReturn().response.contentAsString)["imageUrl"] as String

    // --- 축소

    /** 4000×3000 은 요즘 폰의 평범한 사진 크기다. */
    @Test
    fun `큰 사진도 등록에 성공하고 저장본의 긴 변은 상한 이하로 줄어든다`() {
        val original = photoJpeg(4000, 3000)

        val stored = decodeImage(storedBytesOf(upload(original).andExpect(status().isCreated)))

        assertEquals(mediaProperties.maxEdge, stored.width, "긴 변이 상한(1600)으로 맞춰져야 한다")
        // 4:3 비율이라 1600 의 짝은 1200 이다
        assertEquals(1200, stored.height, "가로세로 비율이 유지되어야 한다")
    }

    @Test
    fun `줄인 저장본은 원본보다 작다 — 디스크와 AI 비용이 실제로 통제된다`() {
        val original = photoJpeg(4000, 3000)
        val stored = storedBytesOf(upload(original).andExpect(status().isCreated))

        assertTrue(
            stored.size < original.size / 2,
            "저장본이 원본의 절반보다도 작아야 한다 (원본 ${original.size}B, 저장본 ${stored.size}B)",
        )
    }

    /** 재인코딩은 되돌릴 수 없는 손실이라 줄일 것이 없는 사진은 원본 그대로 둔다. */
    @Test
    fun `이미 작은 이미지는 한 바이트도 건드리지 않는다`() {
        val original = photoJpeg(800, 600)

        val stored = storedBytesOf(upload(original).andExpect(status().isCreated))

        assertContentEquals(original, stored, "상한 이하의 이미지는 원본 바이트 그대로 저장되어야 한다")
    }

    /** 사진을 PNG 로 두면 JPEG 보다 파일이 몇 배 크다. */
    @Test
    fun `투명하지 않은 큰 PNG 는 JPEG 으로 저장된다`() {
        val png = encodePng(photoImage(2400, 1800))

        val url = imageUrlOf(upload(png, "photo.png", "image/png").andExpect(status().isCreated))

        assertTrue(url.endsWith(".jpg"), "확장자가 실제 저장 형식을 따라야 한다: $url")
        // 확장자만 바뀌고 내용이 PNG 면 contentTypeOf 가 틀리므로 실제 바이트를 본다
        val stored = checkNotNull(mediaStorage.read(url.removePrefix("/media/")))
        assertEquals(ImageType.JPEG, ImageType.detect(stored))
        assertTrue(stored.size < png.size, "사진을 PNG 로 두면 JPEG 보다 훨씬 크다")
    }

    // --- EXIF 방향

    /**
     * 폰 세로 사진은 가로 픽셀에 EXIF 방향만 달려 있어 무시하면 옷과 전신 사진이 눕는다.
     * 400×200 에 방향 6(시계 90도)을 달면 적용 시 200×400, 미적용 시 400×200 으로 갈린다.
     */
    @Test
    fun `EXIF 방향이 실제 픽셀에 적용되어 가로세로가 뒤바뀐다`() {
        val sideways = jpegWithExifOrientation(400, 200, orientation = 6)
        // 전제: 올리는 파일의 픽셀은 아직 가로다
        assertEquals(400, decodeImage(sideways).width, "테스트 이미지 자체가 가로로 누워 있어야 한다")

        val stored = decodeImage(storedBytesOf(upload(sideways).andExpect(status().isCreated)))

        assertEquals(200, stored.width, "방향 6 을 적용하면 세로 사진이 된다")
        assertEquals(400, stored.height)
    }

    /** 픽셀을 돌린 뒤 태그가 남으면 뷰어가 한 번 더 돌린다. 위치정보 같은 개인정보도 함께 지워진다. */
    @Test
    fun `방향을 적용한 뒤 EXIF 는 남지 않는다`() {
        val sideways = jpegWithExifOrientation(400, 200, orientation = 6)

        val stored = storedBytesOf(upload(sideways).andExpect(status().isCreated))

        val orientation = ImageMetadataReader.readMetadata(stored.inputStream())
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifDirectoryBase.TAG_ORIENTATION)
        assertNull(orientation, "저장본에 방향 태그가 남아 있으면 뷰어가 한 번 더 돌린다")
    }

    /** 방향 1(정상)은 돌릴 것이 없다. */
    @Test
    fun `방향 태그가 정상이면 재인코딩하지 않는다`() {
        val upright = jpegWithExifOrientation(400, 200, orientation = 1)

        val stored = storedBytesOf(upload(upright).andExpect(status().isCreated))

        assertContentEquals(upright, stored)
    }

    // --- 투명도

    /**
     * 배경을 지운 옷 사진을 JPEG 으로 인코딩하면 투명한 자리가 칠해진다(보통 검게).
     * 작으면 손대지 않는 경로로 빠지므로 상한(1600)보다 크게 만든다.
     */
    @Test
    fun `투명한 PNG 는 줄여도 PNG 로 남고 투명한 자리가 칠해지지 않는다`() {
        val cutout = transparentPng(2000, 1000)

        val url = imageUrlOf(upload(cutout, "cutout.png", "image/png").andExpect(status().isCreated))
        assertTrue(url.endsWith(".png"), "투명한 이미지는 PNG 로 남아야 한다: $url")

        val stored = decodeImage(checkNotNull(mediaStorage.read(url.removePrefix("/media/"))))
        assertEquals(mediaProperties.maxEdge, stored.width, "줄이는 경로를 실제로 지나야 한다")

        // 가장자리는 보간 영향이 있으므로 투명했던 왼쪽 절반의 안쪽 지점을 본다
        val alpha = stored.getRGB(stored.width / 4, stored.height / 2) ushr 24
        assertEquals(0, alpha, "투명했던 자리가 불투명해졌다 — 누끼 사진이 검게 변하는 상태다")
    }

    // --- 크기

    @Test
    fun `바이트가 아무리 커도 거절하지 않고 받아서 줄인다`() {
        // 뒤에 붙인 바이트는 JPEG 디코더가 무시한다
        val huge = photoJpeg(64, 64) + ByteArray(5_000_000)

        upload(huge).andExpect(status().isCreated)
    }

    /** 8000×8000 은 32비트로 펼치면 256MB 라 ImageNormalizer.decodeBounded 가 건너뛰며 읽는다. */
    @Test
    fun `해상도가 아무리 높아도 받아서 줄인다`() {
        val bomb = encodePng(BufferedImage(8000, 8000, BufferedImage.TYPE_BYTE_GRAY))

        val bytes = storedBytesOf(upload(bomb, "bomb.png", "image/png").andExpect(status().isCreated))

        // 저장본이 실제로 긴 변 상한 안으로 들어왔는지까지 본다
        val stored = checkNotNull(ImageIO.read(java.io.ByteArrayInputStream(bytes)))
        assertTrue(
            maxOf(stored.width, stored.height) <= mediaProperties.maxEdge,
            "저장본이 줄지 않았다: ${stored.width}x${stored.height}",
        )
    }

    /** 다시 상한이 걸려도 톰캣 기본 오류 페이지 대신 JSON 이 나가야 한다. */
    @Test
    fun `컨테이너가 끊은 경우에도 일본어 JSON 으로 답한다`() {
        val handler = ApiExceptionHandler()

        val response = handler.handleMaxUpload(MaxUploadSizeExceededException(1))

        val detail = checkNotNull(response.body).detail
        assertTrue(JAPANESE.matcher(detail).find(), "안내 문구가 일본어가 아니다: $detail")
        assertTrue(!KOREAN.matcher(detail).find(), "사용자에게 한국어가 새어 나갔다: $detail")
    }

    /** 매직 바이트 검사에 더해 픽셀을 읽을 수 없는 파일도 거절한다. */
    @Test
    fun `헤더만 이미지인 위장 파일은 415 로 거절한다`() {
        val fake = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG 시그니처
        ) + ByteArray(512) // 나머지는 0 이라 디코딩할 수 없다

        upload(fake, "fake.png", "image/png")
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_image_type"))
    }

    /** 테스트에서는 application.yml 이 통째로 대체되어 운영 값을 볼 수 없으므로 코드 기본값을 고정한다. */
    @Test
    fun `출하 기본값 — 저장본 1600px, 전송본 768px`() {
        val defaults = MediaProperties()

        assertEquals(1600, defaults.maxEdge)
        assertEquals(768, defaults.aiMaxEdge)
        assertTrue(defaults.aiMaxEdge < defaults.maxEdge)
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private companion object {
        val JAPANESE: Pattern = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
        val KOREAN: Pattern = Pattern.compile("[\\uAC00-\\uD7A3]")
    }
}
