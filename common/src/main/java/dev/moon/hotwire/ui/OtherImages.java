package dev.moon.hotwire.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;

public final class OtherImages {
    private static final long MOST_PICTURE_PIXELS = 16_000_000;

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

    public static @Nullable IconPixels decodePicture(byte[] bytes) throws IOException {
        BufferedImage image;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("the picture could not be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MOST_PICTURE_PIXELS) {
                    throw new IOException("the picture is too large: " + reader.getWidth(0) + " by "
                            + reader.getHeight(0));
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        int width = image.getWidth();
        int height = image.getHeight();
        return IconPixels.ofPicture(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }
}
