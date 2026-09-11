package com.orbit

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 축소·회전 검증용 테스트 이미지들.
 * [pngBytes] 는 4×4 라 픽셀 변화를 확인하기에 부족하다.
 */

/** 그라데이션 사진. 단색이면 JPEG 이 비현실적으로 작게 압축돼 크기 비교가 의미를 잃는다. */
fun photoImage(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val row = IntArray(width)
    for (y in 0 until height) {
        val green = y * 255 / height
        for (x in 0 until width) {
            row[x] = ((x * 255 / width) shl 16) or (green shl 8) or 0x40
        }
        image.setRGB(0, y, width, 1, row, 0, width)
    }
    return image
}

fun photoJpeg(width: Int, height: Int): ByteArray = encode(photoImage(width, height), "jpeg")

/**
 * 왼쪽 절반이 투명한 PNG. 배경을 지운 옷 사진의 최소 재현이다.
 * JPEG 로 재인코딩되면 투명한 절반이 칠해지므로 알파 값으로 확인한다.
 */
fun transparentPng(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val row = IntArray(width)
    for (y in 0 until height) {
        for (x in 0 until width) {
            row[x] = if (x < width / 2) 0x00000000 else (0xFF shl 24) or 0xC81E3C
        }
        image.setRGB(0, y, width, 1, row, 0, width)
    }
    return encode(image, "png")
}

/**
 * EXIF Orientation 태그가 든 JPEG. metadata-extractor 는 읽기 전용이라 SOI 뒤에 APP1 을 직접 끼워 넣는다.
 *   FF E1 | 길이(2) | "Exif\0\0" | TIFF 헤더(빅엔디안) | IFD0(항목 1개: Orientation)
 * JPEG 리더는 모르는 APP 세그먼트를 건너뛰므로 픽셀은 그대로 읽힌다.
 */
fun jpegWithExifOrientation(width: Int, height: Int, orientation: Int): ByteArray {
    val jpeg = photoJpeg(width, height)
    check(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "SOI 로 시작하지 않는다" }

    val tiff = byteArrayOf(
        0x4D, 0x4D, 0x00, 0x2A, // "MM" + 42, 빅엔디안 TIFF
        0x00, 0x00, 0x00, 0x08, // IFD0 오프셋
        0x00, 0x01, // 항목 1개
        0x01, 0x12, // 태그 0x0112 = Orientation
        0x00, 0x03, // 타입 SHORT
        0x00, 0x00, 0x00, 0x01, // 개수 1
        // SHORT 는 4바이트 값 칸의 앞 2바이트에 들어가고 뒤 2바이트는 패딩이다
        (orientation shr 8).toByte(), orientation.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, // 다음 IFD 없음
    )
    // "Exif" + NUL + NUL 이 있어야 리더가 APP1 을 EXIF 로 인정한다
    val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff
    val length = payload.size + 2 // 길이 필드 자신을 포함한다
    val app1 = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(),
        (length shr 8).toByte(), length.toByte(),
    ) + payload

    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + jpeg.copyOfRange(2, jpeg.size)
}

fun decodeImage(bytes: ByteArray): BufferedImage =
    checkNotNull(ImageIO.read(bytes.inputStream())) { "이미지를 읽지 못했다" }

private fun encode(image: BufferedImage, format: String): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, format, out)
    return out.toByteArray()
}
