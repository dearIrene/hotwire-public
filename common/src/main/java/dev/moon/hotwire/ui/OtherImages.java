package dev.moon.hotwire.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

public final class OtherImages {
    private OtherImages() {
    }

    public static boolean isKnown(byte[] bytes) {
        return bytes.length > 4 && ((bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
                || ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8));
    }

    public static @Nullable IconPixels decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("the image could not be read");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        return IconPixels.of(width, height, argb);
    }
}
