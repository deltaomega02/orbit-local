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
 * 사진이 **크다는 이유로 막히지 않는 것**과, 그 대가로 서버가 하는 일.
 *
 * 예전에는 상한이 8MB 였고 요즘 폰 사진은 그걸 예사로 넘는다. 사용자는 413 을 받고,
 * 왜 안 되는지도 사진을 줄이는 방법도 모른 채 등록을 포기했다. 그래서 받는 선은
 * 40MB 로 열고 저장 직전에 서버가 줄인다. 여기서 확인하는 것은 다섯 가지다.
 *  1) 큰 사진이 **성공하고**, 저장본은 작다
 *  2) 작은 사진은 건드리지 않는다 (재인코딩은 되돌릴 수 없는 손실이다)
 *  3) EXIF 방향이 실제 픽셀에 적용되고, 태그는 남지 않는다
 *  4) 투명한 사진이 검게 칠해지지 않는다
 *  5) 안전선(크기·해상도·형식)은 여전히 살아 있다
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

    // ── 축소 ──────────────────────────────────────────────────────

    /**
     * 이 테스트가 이 파일의 존재 이유다. 4000×3000 은 요즘 폰의 평범한 사진 크기고,
     * 예전에는 이 크기가 곧 8MB 상한에 걸려 거절이었다.
     */
    @Test
    fun `큰 사진도 등록에 성공하고 저장본의 긴 변은 상한 이하로 줄어든다`() {
        val original = photoJpeg(4000, 3000)

        val stored = decodeImage(storedBytesOf(upload(original).andExpect(status().isCreated)))

        assertEquals(mediaProperties.maxEdge, stored.width, "긴 변이 상한(1600)으로 맞춰져야 한다")
        // 비율 유지. 4000:3000 = 4:3 이므로 1600 의 짝은 1200 이다. 여기가 어긋나면
        // 사진이 늘어나거나 눌린 채 저장된다.
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

    /**
     * 재인코딩은 되돌릴 수 없다. 줄일 것이 없는 사진까지 다시 구우면 얻는 것 없이
     * 화질만 잃고, 정규화를 두 번 통과한 사진은 두 번 열화된다.
     */
    @Test
    fun `이미 작은 이미지는 한 바이트도 건드리지 않는다`() {
        val original = photoJpeg(800, 600)

        val stored = storedBytesOf(upload(original).andExpect(status().isCreated))

        assertContentEquals(original, stored, "상한 이하의 이미지는 원본 바이트 그대로 저장되어야 한다")
    }

    /** 사진으로 들어온 PNG 는 JPEG 으로 나간다 — 같은 화면에서 파일 크기가 몇 배 차이난다. */
    @Test
    fun `투명하지 않은 큰 PNG 는 JPEG 으로 저장된다`() {
        val png = encodePng(photoImage(2400, 1800))

        val url = imageUrlOf(upload(png, "photo.png", "image/png").andExpect(status().isCreated))

        assertTrue(url.endsWith(".jpg"), "확장자가 실제 저장 형식을 따라야 한다: $url")
        // 확장자만 바뀌고 내용은 PNG 그대로면 contentTypeOf 가 거짓말을 하고 브라우저가
        // 깨진 이미지를 그린다. 경로가 아니라 실제 바이트를 본다.
        val stored = checkNotNull(mediaStorage.read(url.removePrefix("/media/")))
        assertEquals(ImageType.JPEG, ImageType.detect(stored))
        assertTrue(stored.size < png.size, "사진을 PNG 로 두면 JPEG 보다 훨씬 크다")
    }

    // ── EXIF 방향 ─────────────────────────────────────────────────

    /**
     * **이 프로젝트에서 실제로 났던 사고다.** 폰으로 세로로 찍은 사진은 가로로 저장되고
     * EXIF 에 "돌려서 보여라"는 표시만 들어간다. 서버가 그걸 무시하면 옷과 전신 사진이
     * 옆으로 누운 채 저장되고, 가상 착용 결과도 누운 사람이 된다.
     *
     * 가로 400 × 세로 200 에 방향 6(시계 90도)을 달아 올린다. 태그를 제대로 적용했다면
     * 저장본은 **200 × 400** 이어야 한다. 픽셀을 건드리지 않았다면 400 × 200 그대로다 —
     * 두 결과가 명확히 갈리므로 이 검증은 애매하게 통과할 수 없다.
     */
    @Test
    fun `EXIF 방향이 실제 픽셀에 적용되어 가로세로가 뒤바뀐다`() {
        val sideways = jpegWithExifOrientation(400, 200, orientation = 6)
        // 전제 확인: 올리는 파일의 픽셀은 아직 눕혀져 있다. 이게 깨지면 아래 검증은 무의미하다.
        assertEquals(400, decodeImage(sideways).width, "테스트 이미지 자체가 가로로 누워 있어야 한다")

        val stored = decodeImage(storedBytesOf(upload(sideways).andExpect(status().isCreated)))

        assertEquals(200, stored.width, "방향 6 을 적용하면 세로 사진이 된다")
        assertEquals(400, stored.height)
    }

    /**
     * 픽셀을 돌려 놓고 태그를 남기면 뷰어가 한 번 더 돌려서 **두 번 도는** 사진이 된다.
     * 원래 버그보다 나쁘다. 덤으로 위치정보 같은 개인정보도 함께 사라진다.
     */
    @Test
    fun `방향을 적용한 뒤 EXIF 는 남지 않는다`() {
        val sideways = jpegWithExifOrientation(400, 200, orientation = 6)

        val stored = storedBytesOf(upload(sideways).andExpect(status().isCreated))

        val orientation = ImageMetadataReader.readMetadata(stored.inputStream())
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifDirectoryBase.TAG_ORIENTATION)
        assertNull(orientation, "저장본에 방향 태그가 남아 있으면 뷰어가 한 번 더 돌린다")
    }

    /** 방향 1(정상)은 돌릴 것이 없다. 그런 사진까지 다시 굽지 않는다. */
    @Test
    fun `방향 태그가 정상이면 재인코딩하지 않는다`() {
        val upright = jpegWithExifOrientation(400, 200, orientation = 1)

        val stored = storedBytesOf(upload(upright).andExpect(status().isCreated))

        assertContentEquals(upright, stored)
    }

    // ── 투명도 ────────────────────────────────────────────────────

    /**
     * 배경을 지운 옷 사진(누끼)을 JPEG 으로 구우면 투명했던 자리가 통째로 칠해진다.
     * 보통 검게 나오고, 그건 압축이 아니라 사진을 망가뜨리는 것이다.
     *
     * 줄이는 과정을 실제로 거치도록 상한(1600)보다 크게 만든다 — 작으면 손대지 않는
     * 경로로 빠져서 이 검증이 아무것도 확인하지 못한다.
     */
    @Test
    fun `투명한 PNG 는 줄여도 PNG 로 남고 투명한 자리가 칠해지지 않는다`() {
        val cutout = transparentPng(2000, 1000)

        val url = imageUrlOf(upload(cutout, "cutout.png", "image/png").andExpect(status().isCreated))
        assertTrue(url.endsWith(".png"), "투명한 이미지는 PNG 로 남아야 한다: $url")

        val stored = decodeImage(checkNotNull(mediaStorage.read(url.removePrefix("/media/"))))
        assertEquals(mediaProperties.maxEdge, stored.width, "줄이는 경로를 실제로 지나야 한다")

        // 왼쪽 절반은 원래 완전히 투명했다. 가장자리는 보간의 영향을 받을 수 있으므로
        // 확실히 안쪽인 지점을 본다.
        val alpha = stored.getRGB(stored.width / 4, stored.height / 2) ushr 24
        assertEquals(0, alpha, "투명했던 자리가 불투명해졌다 — 누끼 사진이 검게 변하는 상태다")
    }

    // ── 크기 ──────────────────────────────────────────────────────

    /**
     * **크기로는 거절하지 않는다.**
     *
     * 여기에는 "상한을 넘으면 413" 을 고정하는 테스트가 있었다. 상한을 8MB 에서
     * 40MB 로 올린 뒤에도 그 선을 넘는 사진이 또 나왔고, 결국 숫자를 고르는 일
     * 자체가 틀린 문제였다. 사용자는 자기 사진이 몇 MB 인지 모르고, 알아도 줄이는
     * 방법을 모른다.
     *
     * 그래서 규칙이 반대로 뒤집혔다. 큰 파일은 받아서 줄인다.
     */
    @Test
    fun `바이트가 아무리 커도 거절하지 않고 받아서 줄인다`() {
        // 테스트 컨텍스트의 옛 상한(2MB)을 훌쩍 넘기는 파일. 뒤에 붙인 쓰레기는
        // JPEG 디코더가 무시한다 — 여기서 보려는 것은 "크기 때문에 막히는가" 뿐이다.
        val huge = photoJpeg(64, 64) + ByteArray(5_000_000)

        upload(huge).andExpect(status().isCreated)
    }

    /**
     * 픽셀 수도 거절 사유가 아니다.
     *
     * 8000×8000(6400만 픽셀)은 32비트로 펼치면 256MB 라, 예전에는 힙을 지키려고
     * 413 으로 돌려보냈다. 지금은 건너뛰며 읽어서(ImageNormalizer.decodeBounded)
     * 메모리를 넘기지 않고 처리한다. 사용자 입장에서 "왜 안 되는지 모를 거절"이
     * 하나 사라진다.
     */
    @Test
    fun `해상도가 아무리 높아도 받아서 줄인다`() {
        val bomb = encodePng(BufferedImage(8000, 8000, BufferedImage.TYPE_BYTE_GRAY))

        val bytes = storedBytesOf(upload(bomb, "bomb.png", "image/png").andExpect(status().isCreated))

        // 받기만 하고 끝나면 의미가 없다. 저장본이 실제로 상한(긴 변) 안으로
        // 들어왔는지까지 본다.
        val stored = checkNotNull(ImageIO.read(java.io.ByteArrayInputStream(bytes)))
        assertTrue(
            maxOf(stored.width, stored.height) <= mediaProperties.maxEdge,
            "저장본이 줄지 않았다: ${stored.width}x${stored.height}",
        )
    }

    /**
     * 상한을 걸지 않아도 컨테이너가 끊는 경로 자체는 남겨 둔다. 누군가 다시 상한을
     * 걸었을 때 사용자가 JSON 대신 톰캣 기본 오류 페이지를 보면 안 된다.
     */
    @Test
    fun `컨테이너가 끊은 경우에도 일본어 JSON 으로 답한다`() {
        val handler = ApiExceptionHandler()

        val response = handler.handleMaxUpload(MaxUploadSizeExceededException(1))

        val detail = checkNotNull(response.body).detail
        assertTrue(JAPANESE.matcher(detail).find(), "안내 문구가 일본어가 아니다: $detail")
        assertTrue(!KOREAN.matcher(detail).find(), "사용자에게 한국어가 새어 나갔다: $detail")
    }

    /**
     * 매직 바이트 검증은 그대로 살아 있다. 여기에 한 겹을 더 얹었을 뿐이다 —
     * 헤더만 PNG 인 척하고 픽셀을 못 읽는 파일은 이미지가 아니다.
     */
    @Test
    fun `헤더만 이미지인 위장 파일은 415 로 거절한다`() {
        val fake = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG 시그니처
        ) + ByteArray(512) // 그 뒤는 전부 0 — 어떤 디코더도 읽을 수 없다

        upload(fake, "fake.png", "image/png")
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_image_type"))
    }

    /**
     * 설정 파일은 테스트에서 통째로 대체되므로(test/application.yml) 운영 값이 실제로
     * 무엇인지는 여기서 못 본다. 코드 기본값을 직접 못박아 두면 두 곳이 어긋나도
     * 최소한 "우리가 의도한 값"은 한 곳에 남는다.
     */
    @Test
    fun `출하 기본값 — 저장본 1600px, 전송본 768px`() {
        val defaults = MediaProperties()

        assertEquals(1600, defaults.maxEdge)
        // 저장본과 전송본의 기준이 다르다는 것 자체가 설계다. 같아지면 둘 중 하나가 손해다.
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
