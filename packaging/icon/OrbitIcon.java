import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Windows 실행 파일에 박을 아이콘(.ico)을 만든다.
 *
 *   java packaging/icon/OrbitIcon.java packaging/windows/orbit.ico
 *
 * 왜 코드로 그리는가. 디자인 도구로 만든 png 를 커밋하면 "이 파일 어디서 나왔지"가
 * 남고, 크기를 하나 더 넣고 싶을 때 손댈 데가 저장소 밖에 생긴다. 트레이 아이콘
 * (DesktopIntegration.appIcon)과 같은 도형을 같은 규칙으로 그리므로, 작업 표시줄과
 * 알림 영역의 아이콘이 서로 다르게 보이는 일도 없다.
 *
 * .ico 안에는 크기별 이미지를 여러 장 넣는다. Windows 는 쓰는 자리마다 다른 크기를
 * 꺼내 쓰고(알림 영역 16, 바탕화면 48, 큰 아이콘 보기 256), 필요한 크기가 없으면
 * 있는 것을 늘려서 뭉갠 그림을 보여준다.
 *
 * 작은 크기는 무압축 DIB(BMP)로, 256 만 PNG 로 넣는다. PNG 압축 아이콘은 Vista
 * 이후 어디서나 읽히지만 아주 오래된 코드 경로에서 가끔 무시된다. 반대로 256 을
 * DIB 로 넣으면 그것만으로 256KB 가 넘는다. 각 크기에서 더 안전한 쪽을 고른 것이다.
 */
public final class OrbitIcon {

    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "orbit.ico");
        Files.createDirectories(out.toAbsolutePath().getParent());

        byte[][] payloads = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            BufferedImage image = draw(SIZES[i]);
            payloads[i] = SIZES[i] >= 256 ? png(image) : dib(image);
        }

        try (OutputStream out0 = Files.newOutputStream(out)) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            writeShort(head, 0);              // reserved
            writeShort(head, 1);              // type: icon
            writeShort(head, SIZES.length);

            int offset = 6 + 16 * SIZES.length;
            for (int i = 0; i < SIZES.length; i++) {
                head.write(SIZES[i] >= 256 ? 0 : SIZES[i]);  // 256 은 0 으로 적는 것이 규격이다
                head.write(SIZES[i] >= 256 ? 0 : SIZES[i]);
                head.write(0);                // 팔레트 없음
                head.write(0);                // reserved
                writeShort(head, 1);          // planes
                writeShort(head, 32);         // bpp
                writeInt(head, payloads[i].length);
                writeInt(head, offset);
                offset += payloads[i].length;
            }
            out0.write(head.toByteArray());
            for (byte[] payload : payloads) out0.write(payload);
        }

        System.out.println("wrote " + out.toAbsolutePath() + " (" + Files.size(out) + " bytes)");
    }

    /** 트레이 아이콘과 같은 도형 — 남색 원 위에 금색 원, 32px 이상이면 가운데에 O. */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0x1F, 0x2A, 0x44));
        g.fillOval(0, 0, size - 1, size - 1);
        g.setColor(new Color(0xF2, 0xC9, 0x6B));
        int inset = Math.max(2, (int) (size * 0.28));
        g.fillOval(inset, inset, size - inset * 2, size - inset * 2);
        if (size >= 32) {
            g.setColor(new Color(0x1F, 0x2A, 0x44));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (size * 0.36)));
            var fm = g.getFontMetrics();
            g.drawString("O", (size - fm.stringWidth("O")) / 2f, (size + fm.getAscent() - fm.getDescent()) / 2f);
        }
        g.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    /**
     * 아이콘용 DIB. 일반 BMP 와 두 가지가 다르다 — 파일 헤더가 없고, 높이를 실제의
     * 두 배로 적는다(색 데이터 뒤에 1bpp AND 마스크가 붙는 자리를 포함하기 때문).
     * 32비트라 투명도는 알파 채널이 담당하지만, 마스크 자리는 비어 있어도 있어야 한다.
     */
    private static byte[] dib(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeInt(out, 40);        // BITMAPINFOHEADER 크기
        writeInt(out, w);
        writeInt(out, h * 2);     // XOR + AND
        writeShort(out, 1);
        writeShort(out, 32);
        writeInt(out, 0);         // BI_RGB
        writeInt(out, w * h * 4);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);

        for (int y = h - 1; y >= 0; y--) {      // DIB 는 아래에서 위로 쌓는다
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                out.write(argb & 0xFF);          // B
                out.write((argb >> 8) & 0xFF);   // G
                out.write((argb >> 16) & 0xFF);  // R
                out.write((argb >> 24) & 0xFF);  // A
            }
        }

        int maskRow = ((w + 31) / 32) * 4;       // 1bpp, 4바이트 정렬
        for (int i = 0; i < maskRow * h; i++) out.write(0);

        return out.toByteArray();
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
