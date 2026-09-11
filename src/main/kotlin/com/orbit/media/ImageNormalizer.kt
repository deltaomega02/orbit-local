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

/** 픽셀 수 상한 초과. 413. 바이트 크기와 별개로 디코드 시 메모리(OOM)를 막기 위한 것. */
class ImagePixelsTooLargeException(val maxPixels: Long) :
    RuntimeException("이미지 픽셀 수가 너무 많습니다 (상한 ${maxPixels}픽셀)")

/** 형식이 바뀔 수 있으므로(PNG→JPEG) 바이트와 형식을 함께 돌려준다. */
class NormalizedImage(val bytes: ByteArray, val type: ImageType)

/**
 * 업로드 이미지를 방향 보정·축소·위치정보 제거한다.
 * 이미 작고 방향이 정상이며 위치정보가 없으면 재인코딩 손실을 피하려고 바이트 그대로 둔다.
 */
@Component
class ImageNormalizer(private val properties: MediaProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 저장본. 화면에서 다시 보므로 AI 전송본보다 큰 [MediaProperties.maxEdge] 기준. */
    fun forStorage(bytes: ByteArray, type: ImageType): NormalizedImage =
        normalize(bytes, type, properties.maxEdge)

    /**
     * AI 전송본. base64 로 부풀어 토큰 비용이 되므로 [MediaProperties.aiMaxEdge] 로 작게 줄인다.
     * 가상 착용 입력에는 결과 디테일을 위해 쓰지 않는다.
     */
    fun forAiPayload(bytes: ByteArray, type: ImageType): NormalizedImage =
        normalize(bytes, type, properties.aiMaxEdge)

    /**
     * 가상 착용 결과를 자르지 않고 고정 캔버스에 맞춰 넣는다(신발까지 보여야 함).
     * 남는 자리는 같은 사진을 축소 후 확대해 흐리게 깔고 흰 막을 씌운다.
     */
    fun fitToTryonCanvas(bytes: ByteArray, type: ImageType): NormalizedImage {
        // WEBP 디코더가 없어 그대로 둔다
        if (type == ImageType.WEBP) return NormalizedImage(bytes, type)
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return NormalizedImage(bytes, type)

        val cw = properties.tryonCanvasWidth
        val ch = properties.tryonCanvasHeight
        // 이미 캔버스 크기면 재인코딩하지 않는다
        if (source.width == cw && source.height == ch) return NormalizedImage(bytes, type)

        val canvas = BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawBlurredBackdrop(g, source, cw, ch)

            // contain. 원본보다 크게 늘리지는 않는다
            val scale = minOf(cw.toDouble() / source.width, ch.toDouble() / source.height, 1.0)
            val w = max(1, Math.round(source.width * scale).toInt())
            val h = max(1, Math.round(source.height * scale).toInt())
            val fitted = if (w == source.width && h == source.height) source else downscaleTo(source, w, h)
            g.drawImage(fitted, (cw - w) / 2, (ch - h) / 2, null)
        } finally {
            g.dispose()
        }
        return NormalizedImage(encodeJpeg(canvas), ImageType.JPEG)
    }

    private fun drawBlurredBackdrop(g: java.awt.Graphics2D, source: BufferedImage, cw: Int, ch: Int) {
        // cover 크기의 1/BLUR_DIVISOR 로 줄였다가 늘려 흐림 효과를 낸다(컨볼루션보다 빠름)
        val cover = max(cw.toDouble() / source.width, ch.toDouble() / source.height)
        val tinyW = max(1, Math.round(source.width * cover / BLUR_DIVISOR).toInt())
        val tinyH = max(1, Math.round(source.height * cover / BLUR_DIVISOR).toInt())
        val tiny = downscaleTo(source, tinyW, tinyH)

        val drawW = Math.round(source.width * cover).toInt()
        val drawH = Math.round(source.height * cover).toInt()
        g.drawImage(tiny, (cw - drawW) / 2, (ch - drawH) / 2, drawW, drawH, null)

        // 흰 막
        val previous = g.composite
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, BACKDROP_VEIL)
        g.color = Color.WHITE
        g.fillRect(0, 0, cw, ch)
        g.composite = previous
    }

    /** [downscale] 과 달리 목표 크기를 직접 받는다. */
    private fun downscaleTo(source: BufferedImage, width: Int, height: Int): BufferedImage {
        var current = source
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = redraw(current, max(1, current.width / 2), max(1, current.height / 2))
        }
        return if (current.width == width && current.height == height) current else redraw(current, width, height)
    }

    private fun normalize(bytes: ByteArray, type: ImageType, maxEdge: Int): NormalizedImage {
        // JDK 21 ImageIO 에 WebP 디코더가 없으므로 거절하지 않고 그대로 통과시킨다
        if (type == ImageType.WEBP) return NormalizedImage(bytes, type)

        val (width, height) = dimensionsOf(bytes)
            ?: throw UnsupportedImageTypeException("画像を読み取れませんでした")

        val meta = readMetadata(bytes)
        val needsRotate = meta.orientation != ORIENTATION_NORMAL
        val needsResize = max(width, height) > maxEdge
        // GPS 정보가 있으면 크기가 괜찮아도 재인코딩해 제거한다
        val needsStrip = meta.hasLocation

        if (!needsRotate && !needsResize && !needsStrip) return NormalizedImage(bytes, type)

        val decoded = decodeBounded(bytes, width, height)
            // 헤더만 흉내 낸 깨진 파일은 여기서 걸린다
            ?: throw UnsupportedImageTypeException("画像を読み取れませんでした")

        var image = applyOrientation(decoded, meta.orientation)
        if (max(image.width, image.height) > maxEdge) image = downscale(image, maxEdge)

        // 기본은 JPEG. 투명 픽셀이 있으면(누끼 사진) JPEG 에서 검게 칠해지므로 PNG 로 둔다
        return if (hasTransparentPixel(image)) {
            NormalizedImage(encodePng(image), ImageType.PNG)
        } else {
            NormalizedImage(encodeJpeg(image), ImageType.JPEG)
        }
    }

    // --- EXIF

    private class Meta(val orientation: Int, val hasLocation: Boolean)

    /** 메타데이터 파싱 실패는 업로드를 거절하지 않고 정보 없음으로 처리한다. */
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
     * EXIF 방향을 실제 픽셀에 적용한다. 재인코딩 시 EXIF 가 빠지므로 뷰어가 두 번 돌리지 않는다.
     * 90도 배수·반전뿐이라 최근접 보간으로 픽셀을 그대로 옮긴다.
     */
    private fun applyOrientation(decoded: BufferedImage, orientation: Int): BufferedImage {
        // 큰 사진의 불필요한 전체 복사로 OOM 이 나지 않도록 그대로 돌려준다
        if (orientation == ORIENTATION_NORMAL) return decoded

        // 인덱스 컬러·16비트 PNG 는 변환 중 색이 깨지거나 예외가 나므로 표준 타입으로 바꾼다
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

        // 5~8 은 가로세로가 바뀐다
        val swapped = orientation >= 5
        val target = BufferedImage(
            if (swapped) source.height else source.width,
            if (swapped) source.width else source.height,
            canonicalType(source),
        )
        return AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR)
            .filter(source, target)
    }

    // --- 크기

    /** 긴 변이 [maxEdge] 가 되도록 줄인다. 한 번에 줄이면 계단 현상이 생겨 절반씩 단계적으로 줄인다. */
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

    /**
     * [MediaProperties.maxPixels] 를 넘는 사진은 ImageIO 소스 서브샘플링으로 건너뛰며 읽어 메모리를 제한한다.
     * maxPixels 는 최종 크기보다 넉넉해야 이후 [downscale] 에서 화질이 유지된다.
     */
    private fun decodeBounded(bytes: ByteArray, width: Int, height: Int): BufferedImage? {
        val pixels = width.toLong() * height
        if (pixels <= properties.maxPixels) {
            return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        }

        // 면적이 1/n² 로 줄므로 n 은 넓이 비의 제곱근 올림
        val step = Math.ceil(Math.sqrt(pixels.toDouble() / properties.maxPixels)).toInt().coerceAtLeast(2)
        log.info("사진이 {}x{} 라 {}픽셀마다 하나씩 읽는다", width, height, step)

        return runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
                try {
                    reader.input = input
                    val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                    reader.read(0, param)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    /** 디코드 방식을 정하기 위해 헤더만 읽어 크기를 구한다. */
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

    // --- 인코딩

    /** 알파 채널이 있어도 전부 불투명한 PNG 가 흔하므로 실제 투명 픽셀이 있는지 본다. */
    private fun hasTransparentPixel(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false
        // 알파를 8비트로 가정한다. 16비트면 투명으로 오판하지만 결과가 PNG 유지라 안전하다
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

    /** 알파 채널이 남으면 JDK JPEG 라이터가 4채널로 써서 색이 뒤집히므로 흰 배경에 합성해 3채널로 만든다. */
    private fun encodeJpeg(image: BufferedImage): ByteArray {
        // 알파가 없으면 복사하지 않는다
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

    /** 그리기·회전 대상 타입을 INT_ARGB/INT_RGB 로 모아 알파 검사가 0~255 스케일에서 동작하게 한다. */
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

        /** 클수록 뒷배경이 더 흐려진다. */
        const val BLUR_DIVISOR = 26.0

        /** 뒷배경 위 흰 막의 불투명도. */
        const val BACKDROP_VEIL = 0.28f
    }
}
