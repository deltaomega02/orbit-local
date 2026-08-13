package com.orbit.media

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.GpsDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/**
 * 픽셀 수가 비상식적인 이미지. 413 으로 변환된다.
 *
 * 바이트 상한([MediaProperties.maxFileSize])과 **다른 축**이다. 잘 압축된 PNG 한 장이
 * 20MB 로도 30000×30000 이 될 수 있고, 그걸 디코드하면 픽셀 버퍼만 3.6GB 다 —
 * 바이트 상한을 통과했다는 사실은 메모리에 대해 아무것도 보장하지 않는다.
 * 설치본은 힙이 제한돼 있으므로(-Xmx) 여기서 끊지 않으면 OOM 으로 서버가 죽는다.
 */
class ImagePixelsTooLargeException(val maxPixels: Long) :
    RuntimeException("이미지 픽셀 수가 너무 많습니다 (상한 ${maxPixels}픽셀)")

/** 정규화 결과. 형식이 바뀔 수 있으므로(PNG→JPEG) 바이트와 형식을 함께 돌려준다. */
class NormalizedImage(val bytes: ByteArray, val type: ImageType)

/**
 * 업로드된 이미지를 "받아도 되는 모양"으로 손질한다.
 *
 * ## 왜 상한을 올리는 대신 서버가 줄이는가
 *
 * 상한만 올리면 디스크와 AI 호출 비용이 그대로 사용자 손에 넘어간다. 반대로 상한을
 * 낮게 두면 요즘 폰 사진(12MP·4~8MB, HDR 이면 그 이상)이 그냥 거절되고, 사용자는
 * **왜 안 되는지도 모르고 줄일 방법도 모른다.** 그래서 받는 선은 넉넉히 열고
 * (40MB — 이 이상은 사진이 아니라 뭔가 잘못된 것이다) 저장 직전에 서버가 줄인다.
 *
 * ## 손대지 않는 경우
 *
 * 이미 작고, 방향이 정상이고, 위치정보도 없는 이미지는 **바이트 그대로 저장한다.**
 * 재인코딩은 되돌릴 수 없는 손실이라, 얻을 것이 없으면 하지 않는 것이 맞다.
 * (그래서 이미 정규화를 거친 저장본을 다시 통과시켜도 두 번 열화되지 않는다.)
 */
@Component
class ImageNormalizer(private val properties: MediaProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 디스크에 남길 형태로 줄인다. 긴 변 [MediaProperties.maxEdge] 기준.
     *
     * 이 기준은 **전송본과 다르다**([forAiPayload]). 저장본은 사용자가 화면에서 다시
     * 들여다보는 원본 역할이라 디테일이 남아야 하고, 전송본은 모델이 한 번 보고 버리는
     * 입력이라 훨씬 작아도 된다. 한 값으로 합치면 둘 중 하나가 반드시 손해를 본다.
     */
    fun forStorage(bytes: ByteArray, type: ImageType): NormalizedImage =
        normalize(bytes, type, properties.maxEdge)

    /**
     * AI 에 실어 보낼 형태로 줄인다. 긴 변 [MediaProperties.aiMaxEdge] 기준.
     *
     * 원본 Orbit(Django)도 768px 로 줄여 보냈고, 그 판단은 옳았다. 옷의 종류·색·소재를
     * 읽는 데 1600px 가 필요하지 않은데, base64 는 바이트를 4/3 배로 부풀려 그대로
     * 요청 크기와 토큰 비용이 된다. 저장본을 그대로 보내면 사진 한 장에 수 MB 다.
     *
     * 가상 착용([com.orbit.service.OutfitAiService.generateTryOn])에는 **일부러 적용하지
     * 않았다.** 그쪽의 출력은 사용자가 실제로 들여다보는 사진이고, 입력 해상도가 그대로
     * 결과의 디테일(무늬·단추·질감)이 된다. 저장본이 이미 1600px 로 눌려 있어 페이로드
     * 상한도 그 선에서 걸린다.
     */
    fun forAiPayload(bytes: ByteArray, type: ImageType): NormalizedImage =
        normalize(bytes, type, properties.aiMaxEdge)

    private fun normalize(bytes: ByteArray, type: ImageType, maxEdge: Int): NormalizedImage {
        /*
         * WEBP 는 손대지 않고 그대로 통과시킨다. JDK 21 의 ImageIO 에는 WebP 디코더가
         * 없어서 픽셀을 읽을 방법 자체가 없다. 여기서 "못 읽었으니 거절"로 처리하면
         * 지금까지 잘 올라가던 형식이 갑자기 415 가 된다 — 크기 때문에 막히지 않게
         * 하려는 작업이 다른 이유로 막는 결과가 되면 앞뒤가 맞지 않는다.
         */
        if (type == ImageType.WEBP) return NormalizedImage(bytes, type)

        val (width, height) = dimensionsOf(bytes)
            ?: throw UnsupportedImageTypeException("画像を読み取れませんでした")
        if (width.toLong() * height > properties.maxPixels) {
            throw ImagePixelsTooLargeException(properties.maxPixels)
        }

        val meta = readMetadata(bytes)
        val needsRotate = meta.orientation != ORIENTATION_NORMAL
        val needsResize = max(width, height) > maxEdge
        // 위치정보가 붙어 있으면 크기가 괜찮아도 다시 굽는다. "집에서 찍은 옷 사진"에
        // 집 좌표가 실려 남는 것을 그대로 두는 편이, 화질 한 겹보다 나쁘다.
        val needsStrip = meta.hasLocation

        if (!needsRotate && !needsResize && !needsStrip) return NormalizedImage(bytes, type)

        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            // 매직 바이트는 JPEG/PNG 인데 픽셀을 못 읽는다면 이미지가 아니거나 깨진
            // 파일이다. 매직 바이트 검증([ImageType.detect])을 약화시키는 것이 아니라
            // 그 뒤에 한 겹 더 세우는 것이다 — 헤더만 흉내 낸 파일이 여기서 걸린다.
            ?: throw UnsupportedImageTypeException("画像を読み取れませんでした")

        var image = applyOrientation(decoded, meta.orientation)
        if (max(image.width, image.height) > maxEdge) image = downscale(image, maxEdge)

        /*
         * 사진이면 JPEG 가 훨씬 작다(PNG 로 들어온 사진도 마찬가지다). 다만 **투명한
         * 픽셀이 하나라도 있으면 PNG 로 남긴다.** JPEG 에는 알파 채널이 없어서, 배경을
         * 지운 옷 누끼 사진을 JPEG 로 구우면 투명했던 자리가 통째로 검게 칠해진다.
         * 그건 압축이 아니라 사진을 망가뜨리는 것이다.
         */
        return if (hasTransparentPixel(image)) {
            NormalizedImage(encodePng(image), ImageType.PNG)
        } else {
            NormalizedImage(encodeJpeg(image), ImageType.JPEG)
        }
    }

    // ── EXIF ──────────────────────────────────────────────────────────

    private class Meta(val orientation: Int, val hasLocation: Boolean)

    /**
     * EXIF 를 한 번만 읽어 필요한 두 가지를 뽑는다.
     *
     * 실패는 조용히 "정보 없음"으로 떨어뜨린다. 메타데이터를 못 읽는 것과 이미지가
     * 잘못된 것은 다른 사건이고, 사진 자체는 멀쩡한데 태그 파싱이 실패했다고 업로드를
     * 거절하면 사용자는 손쓸 방법이 없는 오류를 보게 된다.
     */
    private fun readMetadata(bytes: ByteArray): Meta = runCatching {
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        val orientation = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifDirectoryBase.TAG_ORIENTATION)
            ?.takeIf { it in ORIENTATION_NORMAL..ORIENTATION_MAX }
            ?: ORIENTATION_NORMAL
        Meta(orientation, metadata.getFirstDirectoryOfType(GpsDirectory::class.java) != null)
    }.getOrElse {
        log.debug("메타데이터를 읽지 못했다. 방향 보정 없이 진행한다", it)
        Meta(ORIENTATION_NORMAL, false)
    }

    /**
     * EXIF 방향을 **실제 픽셀에** 적용한다.
     *
     * 방향 태그는 "이 사진은 이렇게 돌려서 보여라"는 쪽지일 뿐이라, 픽셀은 카메라
     * 센서가 읽은 그대로 가로로 누워 있다. 우리가 이 쪽지를 무시하면 옷과 전신 사진이
     * 옆으로 누운 채 저장되고, 가상 착용 결과도 누운 사람이 된다.
     *
     * 굽고 나면 EXIF 는 버린다(재인코딩이 알아서 버린다). 태그를 남긴 채 픽셀만 돌리면
     * 뷰어가 한 번 더 돌려서 **두 번 도는** 사진이 된다 — 원래 버그보다 나쁘다.
     *
     * 변환은 전부 90도 배수와 반전뿐이라 최근접 보간으로 충분하고, 그래야 픽셀이
     * 정확히 그대로 옮겨진다(보간을 쓰면 돌리기만 해도 화질이 흐려진다).
     */
    private fun applyOrientation(decoded: BufferedImage, orientation: Int): BufferedImage {
        // 돌릴 것이 없으면 **아무것도 복사하지 않는다.** 5천만 픽셀짜리 사진의 전체
        // 복사본 하나가 200MB 다. 설치본은 힙이 제한돼 있어(-Xmx) 쓸데없는 복사 한 번이
        // 그대로 OOM 이 된다. 아래 축소가 어차피 표준 타입으로 다시 그린다.
        if (orientation == ORIENTATION_NORMAL) return decoded

        // 변환에 넣기 전에는 표준 타입으로 모은다. 인덱스 컬러나 16비트 PNG 를 그대로
        // 넣으면 색이 뭉개지거나 라이브러리가 예외를 던진다.
        val source = canonical(decoded)

        val w = source.width.toDouble()
        val h = source.height.toDouble()
        // AffineTransform(m00, m10, m01, m11, m02, m12) → x' = m00·x + m01·y + m02
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0) // 좌우 반전
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h) // 180도
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h) // 상하 반전
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0) // 전치
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0) // 시계 90도 (세로 사진의 대부분)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w) // 역전치
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w) // 반시계 90도
            else -> return canonical(source)
        }

        // 5~8 은 축이 뒤바뀌므로 결과의 가로세로가 서로 바뀐다. 이걸 빠뜨리면 돌린
        // 그림이 잘려 나간다.
        val swapped = orientation >= 5
        val target = BufferedImage(
            if (swapped) source.height else source.width,
            if (swapped) source.width else source.height,
            canonicalType(source),
        )
        return AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR)
            .filter(source, target)
    }

    // ── 크기 ──────────────────────────────────────────────────────────

    /**
     * 긴 변이 [maxEdge] 가 되도록 비율을 유지해 줄인다.
     *
     * 4000px 에서 1600px 로 **한 번에** 줄이면 원본 픽셀의 대부분이 그냥 버려져
     * 가는 줄무늬와 글자가 계단처럼 깨진다(옷 태그 사진에서 바로 보인다). 절반씩
     * 접어 내려가며 매 단계 이웃 픽셀을 섞으면 그 정보가 결과에 남는다.
     */
    private fun downscale(source: BufferedImage, maxEdge: Int): BufferedImage {
        val ratio = maxEdge.toDouble() / max(source.width, source.height)
        val targetWidth = max(1, Math.round(source.width * ratio).toInt())
        val targetHeight = max(1, Math.round(source.height * ratio).toInt())

        var current = source
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            current = redraw(current, max(1, current.width / 2), max(1, current.height / 2))
        }
        return if (current.width == targetWidth && current.height == targetHeight) {
            current
        } else {
            redraw(current, targetWidth, targetHeight)
        }
    }

    private fun redraw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, canonicalType(source))
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return target
    }

    /** 헤더만 읽어 크기를 본다. **디코드 전에** 알아야 픽셀 상한이 방어가 된다. */
    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return@runCatching null
        input.use {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = it
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    // ── 인코딩 ────────────────────────────────────────────────────────

    /**
     * 알파 채널이 있어도 **실제로 투명한 픽셀이 있는지**를 본다.
     *
     * `colorModel.hasAlpha()` 만 보면 안 된다 — 화면 캡처처럼 알파 채널은 있지만 전부
     * 불투명한 PNG 가 흔하고, 그런 사진까지 PNG 로 남기면 줄인 보람이 없다.
     * 첫 투명 픽셀에서 바로 끝내므로 진짜 누끼 사진에서는 거의 즉시 끝난다.
     */
    private fun hasTransparentPixel(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false
        // 알파를 8비트로 가정한다. 채널이 더 깊은 이미지(16비트 PNG)라면 불투명 픽셀도
        // 255 가 아니게 나와 "투명하다"로 읽히는데, 그 오판의 결과는 **PNG 로 남기는
        // 것**이라 사진이 망가지지는 않는다. 틀려도 안전한 방향으로만 틀린다.
        val alpha = image.alphaRaster ?: return false
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            alpha.getSamples(0, y, image.width, 1, 0, row)
            if (row.any { it != OPAQUE }) return true
        }
        return false
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * JPEG 로 굽는다. 품질 [MediaProperties.jpegQuality].
     *
     * 알파 채널이 남아 있으면 JDK 의 JPEG 라이터가 4채널을 그대로 써서 대부분의
     * 뷰어가 색이 뒤집힌 이미지로 읽는다. 그래서 먼저 흰 배경 위에 눌러 3채널로
     * 만든다. 여기 오는 이미지는 이미 "투명한 픽셀이 없다"가 확인된 것들이라
     * 흰 배경은 실제로는 한 픽셀도 드러나지 않는다.
     */
    private fun encodeJpeg(image: BufferedImage): ByteArray {
        // 알파가 없으면 그대로 넘긴다(JPEG 디코드 결과인 3BYTE_BGR 이 여기 해당한다).
        // 여기서 무조건 복사하면 큰 사진 한 장마다 전체 크기 버퍼가 하나 더 생긴다.
        val opaque = if (!image.colorModel.hasAlpha()) {
            image
        } else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { flat ->
                val g = flat.createGraphics()
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, flat.width, flat.height)
                    g.drawImage(image, 0, 0, null)
                } finally {
                    g.dispose()
                }
            }
        }

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = properties.jpegQuality
            }
            try {
                writer.write(null, IIOImage(opaque, null, null), param)
            } finally {
                writer.dispose()
            }
        }
        return out.toByteArray()
    }

    /**
     * 그리기·회전의 대상 타입. 원본이 16비트 PNG 나 인덱스 컬러여도 여기서 두 가지로
     * 모은다 — 그래야 아래의 알파 검사가 항상 0~255 스케일 위에서 성립한다.
     */
    private fun canonicalType(source: BufferedImage): Int =
        if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB

    private fun canonical(source: BufferedImage): BufferedImage {
        val type = canonicalType(source)
        if (source.type == type) return source
        return redraw(source, source.width, source.height)
    }

    private companion object {
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_MAX = 8
        const val OPAQUE = 255
    }
}
