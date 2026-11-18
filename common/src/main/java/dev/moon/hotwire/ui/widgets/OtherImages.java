package dev.moon.hotwire.ui.widgets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;

public final class OtherImages {
    private static final long MOST_PICTURE_PIXELS = 16_000_000;

    private static final long MOST_ICON_PIXELS = 4096L * 4096;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "gif");

    private OtherImages() {
    }

    public static boolean isKnown(byte[] bytes) {
        return bytes.length > 4 && ((bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
                || ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8));
    }

    public static @Nullable IconPixels decode(byte[] bytes) throws IOException {
        BufferedImage image = read(bytes, MOST_ICON_PIXELS);
        int width = image.getWidth();
        int height = image.getHeight();
        return IconPixels.of(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    public static @Nullable IconPixels decodePicture(byte[] bytes) throws IOException {
        BufferedImage image = read(bytes, MOST_PICTURE_PIXELS);
        int width = image.getWidth();
        int height = image.getHeight();
        return IconPixels.ofPicture(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    public static void checkPngSize(byte[] bytes) throws IOException {
        if (bytes.length < 24 || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            return;
        }
        long width = readInt(bytes, 16) & 0xffff_ffffL;
        long height = readInt(bytes, 20) & 0xffff_ffffL;
        if (width * height > MOST_ICON_PIXELS) {
            throw new IOException("the icon is too large: " + width + " by " + height);
        }
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xff) << 24 | (bytes[at + 1] & 0xff) << 16 | (bytes[at + 2] & 0xff) << 8
                | (bytes[at + 3] & 0xff);
    }

    private static BufferedImage read(byte[] bytes, long mostPixels) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("the picture could not be read");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!FORMATS.contains(format)) {
                    throw new IOException("a picture of the kind " + format + " is not read");
                }
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > mostPixels) {
                    throw new IOException("the picture is too large: " + reader.getWidth(0) + " by "
                            + reader.getHeight(0));
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
