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

/** 415. */
class UnsupportedImageTypeException(message: String) : RuntimeException(message)

/** 413. */
class ImageTooLargeException(val maxBytes: Long) : RuntimeException("이미지가 너무 큽니다 (상한 ${maxBytes}바이트)")

@ConfigurationProperties("orbit.media")
data class MediaProperties(
    /** 기본값은 .gitignore 에 포함된 `data/` 아래. */
    val dir: String = "./data/media",
    // 업로드 크기 상한은 두지 않는다. 큰 사진은 받아서 줄이고, 메모리는 서브샘플링 디코드로 제한한다.
    /** 저장본 긴 변 상한(px). */
    val maxEdge: Int = 1600,
    /** AI 전송본 긴 변 상한(px). */
    val aiMaxEdge: Int = 768,
    /** 이보다 낮으면 니트·데님 질감이 뭉개지기 시작한다. */
    val jpegQuality: Float = 0.85f,
    /** 거절 기준이 아니라 한 번에 디코드할 픽셀 수. 넘으면 서브샘플링해 읽는다([ImageNormalizer.decodeBounded]). */
    val maxPixels: Long = 40_000_000,
    /**
     * 가상 착용 결과 캔버스(px). 기록 목록의 카드 크기를 맞추기 위해 결과를 항상 2:3 캔버스에 넣는다.
     * 높이를 [maxEdge] 와 같게 둬 세로로 긴 사진은 다시 줄이지 않는다.
     */
    val tryonCanvasWidth: Int = 1067,
    val tryonCanvasHeight: Int = 1600,
)

@Configuration
@EnableConfigurationProperties(MediaProperties::class)
class MediaConfig

/** 허용 이미지 형식. Content-Type 은 믿지 않고 매직 바이트로 최종 판단한다. */
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

/** `relativePath` 는 DB 에 저장되고 `/media/{relativePath}` 로 서빙된다. */
data class StoredMedia(val relativePath: String, val type: ImageType)

/**
 * 로컬 디스크 미디어 저장소.
 * 경로 조작과 덮어쓰기를 막기 위해 파일명은 UUID, 디렉터리는 `{종류}/yyyy/MM/dd` 로 나눈다.
 */
@Component
class MediaStorage(
    private val properties: MediaProperties,
    private val normalizer: ImageNormalizer,
    private val clock: Clock,
) {
    private val root: Path = Paths.get(properties.dir).toAbsolutePath().normalize()

    /** 저장 없이 AI 로만 넘기는 analyze 경로도 쓰므로 [store] 와 분리했다. */
    fun validate(bytes: ByteArray, declaredContentType: String?): ImageType {
        if (bytes.isEmpty()) throw IllegalArgumentException("空のファイルはアップロードできません")
        // 크기로는 거절하지 않는다
        val actual = ImageType.detect(bytes)
            ?: throw UnsupportedImageTypeException("jpeg/png/webp 이미지만 업로드할 수 있습니다")
        val declared = declaredContentType?.takeIf { it.isNotBlank() }
        if (declared != null && ImageType.fromMime(declared) != actual) {
            throw UnsupportedImageTypeException("선언한 형식과 실제 파일 형식이 다릅니다")
        }
        return actual
    }

    /**
     * 검증, 정규화, 저장. 모든 저장 경로가 같은 정규화를 거치도록 여기서 한다.
     * 확장자는 [contentTypeOf] 가 맞도록 정규화 후 형식을 따른다.
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

    /** 없거나 잘못된 경로면 null. */
    fun read(relativePath: String): ByteArray? {
        val target = runCatching { resolve(relativePath) }.getOrNull() ?: return null
        if (!Files.isRegularFile(target)) return null
        return Files.readAllBytes(target)
    }

    /** 삭제 실패는 요청을 실패시키지 않는다. */
    fun deleteQuietly(relativePath: String) {
        runCatching { Files.deleteIfExists(resolve(relativePath)) }
    }

    fun contentTypeOf(relativePath: String): String =
        ImageType.fromExtension(relativePath.substringAfterLast('.', ""))?.mime
            ?: "application/octet-stream"

    /** `/media` 요청으로 임의 문자열이 들어오므로 루트 밖을 가리키는 경로(`..`)는 여기서 거절한다. */
    private fun resolve(relativePath: String): Path {
        require(relativePath.isNotBlank()) { "パスが空です" }
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "許可されていないパスです" }
        return target
    }
}
