package com.orbit.media

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** 허용하지 않는 형식의 이미지. 415 로 변환된다. */
class UnsupportedImageTypeException(message: String) : RuntimeException(message)

/** 상한을 넘는 업로드. 413 으로 변환된다. */
class ImageTooLargeException(val maxBytes: Long) : RuntimeException("이미지가 너무 큽니다 (상한 ${maxBytes}바이트)")

@ConfigurationProperties("orbit.media")
data class MediaProperties(
    /** 미디어 루트. 저장소에는 커밋되지 않도록 .gitignore 의 `data/` 안을 기본값으로 둔다. */
    val dir: String = "./data/media",
    /**
     * 업로드 상한. 서블릿 컨테이너 상한(spring.servlet.multipart)도 함께 걸지만,
     * 그건 "컨테이너가 버퍼링을 멈추는 선"이고 이 값은 "애플리케이션이 받아들이는 선"이다.
     *
     * **이 값은 사용자를 막는 선이 아니라 "이건 사진이 아니다"를 거르는 안전선이다.**
     * 예전에는 8MB 였고, 그건 요즘 폰 사진(12MP·HDR·연사 합성)이 예사로 넘는 값이라
     * 멀쩡한 사진이 413 으로 거절됐다. 사용자는 왜 안 되는지도, 사진을 어떻게 줄이는지도
     * 모른다. 그래서 받는 선은 40MB 로 넉넉히 열고, 디스크와 AI 비용은 상한이 아니라
     * 저장 직전의 축소([ImageNormalizer.forStorage])로 통제한다.
     */
    val maxFileSize: DataSize = DataSize.ofMegabytes(40),
    /**
     * 저장본의 긴 변 상한(px). 넘으면 비율을 유지해 줄인다.
     *
     * 1600px 인 이유: 이 앱에서 사진이 가장 크게 쓰이는 자리가 상세 화면과 가상 착용
     * 입력인데, 둘 다 1600px 면 충분하고 그 위는 디스크만 먹는다. 4000×3000 짜리
     * 폰 사진 한 장이 4MB 라면 1600px JPEG 로는 대략 300KB 안팎이 된다.
     */
    val maxEdge: Int = 1600,
    /** AI 로 보내는 전송본의 긴 변 상한(px). 저장본과 기준이 다른 이유는 [ImageNormalizer.forAiPayload] 참고. */
    val aiMaxEdge: Int = 768,
    /**
     * 재인코딩 JPEG 품질. 0.85 는 사진에서 육안으로 원본과 구별하기 어려우면서
     * 파일 크기가 크게 떨어지는 지점이다. 더 올리면 크기만 늘고, 더 내리면 옷의
     * 질감(니트 짜임·데님 결)에서 뭉개짐이 보이기 시작한다.
     */
    val jpegQuality: Float = 0.85f,
    /**
     * 픽셀 수 상한. [maxFileSize] 와 **다른 축의 방어**다 — 잘 압축된 PNG 는 20MB 로도
     * 3만×3만 이 될 수 있고, 디코드하면 픽셀 버퍼만 수 GB 라 설치본의 힙을 그냥 넘긴다.
     * 5천만 픽셀은 지금 나오는 폰 카메라(48~50MP)의 최대치를 다 덮는 값이다.
     */
    val maxPixels: Long = 50_000_000,
    /**
     * 가상 착용 결과를 앉힐 캔버스(px). **결과 사진의 크기는 여기서 정해진다.**
     *
     * 모델의 출력은 입력 전신 사진을 따라가므로, 사진을 바꾸면 결과 비율도 바뀌고
     * 예전에 만든 기록과 새 기록이 서로 다른 모양이 된다. 목록에서 카드가 저마다
     * 다른 높이로 서면 그건 그냥 깨져 보인다. 그래서 저장 직전에 항상 같은 캔버스
     * 위에 앉힌다 — 화면은 사진의 비율을 몰라도 되고, 기록이 몇 년 쌓여도 한 줄로
     * 정렬된다.
     *
     * 2:3 인 이유: 전신 사진은 세로로 길다. 3:4 는 사람을 자르거나 좌우에 큰 여백을
     * 남기고, 9:16 은 목록에서 카드가 화면을 다 먹는다. 2:3 은 인화지 비율이라
     * 눈에 익고 둘 사이에서 가장 덜 손해다.
     *
     * 높이를 [maxEdge] 와 같은 1600 으로 둬서, 세로로 긴 사진은 **줄이지 않고**
     * 그대로 얹힌다(다시 표본화하면 얼굴이 뭉개진다).
     */
    val tryonCanvasWidth: Int = 1067,
    val tryonCanvasHeight: Int = 1600,
)

@Configuration
@EnableConfigurationProperties(MediaProperties::class)
class MediaConfig

/**
 * 허용 이미지 형식.
 *
 * 클라이언트가 보낸 Content-Type 은 그냥 문자열이라 얼마든지 거짓말할 수 있다.
 * 그래서 최종 판단은 항상 파일 앞머리의 시그니처(매직 바이트)로 한다. 선언된
 * 타입은 값이 있을 때만 "화이트리스트에 있고 실제 바이트와 일치하는가"를 보는
 * 1차 관문으로 쓴다.
 */
enum class ImageType(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    ;

    companion object {
        fun fromMime(mime: String?): ImageType? =
            mime?.substringBefore(';')?.trim()?.lowercase()?.let { m -> entries.firstOrNull { it.mime == m } }

        fun fromExtension(ext: String): ImageType? =
            entries.firstOrNull { it.extension == ext.lowercase() }

        /** 매직 바이트로 실제 형식을 판별한다. 모르는 형식이면 null. */
        fun detect(bytes: ByteArray): ImageType? {
            if (bytes.size < 12) return null
            fun at(i: Int) = bytes[i].toInt() and 0xFF
            // JPEG: FF D8 FF
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return JPEG
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 &&
                at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A
            ) {
                return PNG
            }
            // WEBP: "RIFF" ....(size).... "WEBP"
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return WEBP
            return null
        }
    }
}

/** 저장 결과. `relativePath` 가 DB 에 들어가고, 그대로 `/media/{relativePath}` 로 서빙된다. */
data class StoredMedia(val relativePath: String, val type: ImageType)

/**
 * 로컬 디스크 미디어 저장소.
 *
 * 설계에서 신경 쓴 두 가지.
 *  1) **파일명은 UUID.** 원본 파일명을 그대로 쓰면 `../../etc/passwd` 같은 값이
 *     경로에 섞여 들어올 수 있고, 같은 이름끼리 서로를 덮어쓴다. 사용자 입력이
 *     경로에 닿는 지점 자체를 없앴다.
 *  2) **날짜 파티셔닝(`{종류}/yyyy/MM/dd/`).** 한 디렉토리에 파일이 수십만 개
 *     쌓이면 조회·백업·rsync 가 전부 느려진다. 날짜로 쪼개면 자연스럽게 분산되고,
 *     "언제 올린 파일인지"가 경로만 봐도 드러나 운영에서 다루기 쉽다.
 */
@Component
class MediaStorage(
    private val properties: MediaProperties,
    private val normalizer: ImageNormalizer,
    private val clock: Clock,
) {
    /** 루트를 정규화해 두고, 모든 경로 해석이 이 밖으로 나가지 못하게 막는다. */
    private val root: Path = Paths.get(properties.dir).toAbsolutePath().normalize()

    /**
     * 받아들일 수 있는 이미지인지 확인하고 실제 형식을 돌려준다.
     * 저장하지 않고 AI 에만 넘기는 경로(`/api/clothes/analyze`)도 같은 관문을 통과해야 하므로
     * 검증을 [store] 안에 묻어 두지 않고 밖으로 꺼냈다.
     */
    fun validate(bytes: ByteArray, declaredContentType: String?): ImageType {
        if (bytes.isEmpty()) throw IllegalArgumentException("空のファイルはアップロードできません")
        if (bytes.size > properties.maxFileSize.toBytes()) {
            throw ImageTooLargeException(properties.maxFileSize.toBytes())
        }

        val actual = ImageType.detect(bytes)
            ?: throw UnsupportedImageTypeException("jpeg/png/webp 이미지만 업로드할 수 있습니다")
        val declared = declaredContentType?.takeIf { it.isNotBlank() }
        if (declared != null && ImageType.fromMime(declared) != actual) {
            // 선언과 실제가 다르면 둘 중 하나는 거짓이다. 어느 쪽을 믿을지 고민하지 않고 거절한다.
            throw UnsupportedImageTypeException("선언한 형식과 실제 파일 형식이 다릅니다")
        }
        return actual
    }

    /**
     * 검증 → **정규화** → 저장.
     *
     * 정규화가 여기 있는 것이 요점이다. 축소·회전 보정을 호출부에 맡기면 언젠가 한
     * 경로가 빠지고, 그 경로로 들어온 사진만 옆으로 누워서 저장된다(경로 검증을
     * [resolve] 한 곳에 모은 것과 같은 이유다). 지금 store 를 부르는 곳은 옷 사진·전신
     * 사진·가상 착용 결과 셋인데, 셋 다 같은 규칙을 받아야 한다.
     *
     * 확장자는 **정규화된 형식**을 따른다. PNG 로 올라온 사진이 JPEG 로 나갔는데
     * 경로만 `.png` 로 남으면 [contentTypeOf] 가 거짓말을 하고 브라우저가 깨진 이미지를
     * 그린다.
     */
    fun store(category: String, bytes: ByteArray, declaredContentType: String?): StoredMedia {
        val actual = validate(bytes, declaredContentType)
        val normalized = normalizer.forStorage(bytes, actual)
        val today = LocalDate.now(clock)
        val relative = "%s/%04d/%02d/%02d/%s.%s".format(
            category, today.year, today.monthValue, today.dayOfMonth,
            UUID.randomUUID(), normalized.type.extension,
        )
        val target = resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, normalized.bytes)
        return StoredMedia(relative, normalized.type)
    }

    /** 없으면 null. 파일이 사라진 것과 애초에 없는 것을 호출부가 구분할 이유가 없다. */
    fun read(relativePath: String): ByteArray? {
        val target = runCatching { resolve(relativePath) }.getOrNull() ?: return null
        if (!Files.isRegularFile(target)) return null
        return Files.readAllBytes(target)
    }

    /** 교체된 옛 파일을 치운다. 실패해도 요청을 실패시키지 않는다 — 본질은 이미 끝났다. */
    fun deleteQuietly(relativePath: String) {
        runCatching { Files.deleteIfExists(resolve(relativePath)) }
    }

    fun contentTypeOf(relativePath: String): String =
        ImageType.fromExtension(relativePath.substringAfterLast('.', ""))?.mime
            ?: "application/octet-stream"

    /**
     * 상대 경로를 실제 경로로 바꾼다.
     *
     * 지금은 경로가 전부 서버가 만든 UUID 라 사용자 입력이 섞이지 않지만,
     * `/media/…` 로는 임의 문자열이 들어온다. `..` 를 포함한 경로가 루트 밖을
     * 가리키면 여기서 끊는다 — 검사를 호출부에 맡기면 언젠가 한 곳이 빠진다.
     */
    private fun resolve(relativePath: String): Path {
        require(relativePath.isNotBlank()) { "パスが空です" }
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "許可されていないパスです" }
        return target
    }
}
