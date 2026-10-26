package dev.moon.hotwire.modrinth;

import dev.moon.hotwire.ui.IconPixels;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

public final class WebpLossless {
    private static final int MAGIC = 0x2f;
    private static final int CODE_LENGTH_CODES = 19;
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final int NUM_LITERAL_CODES = 256;
    private static final int NUM_LENGTH_CODES = 24;
    private static final int NUM_DISTANCE_CODES = 40;
    private static final int[] GROUP_SIZES = {NUM_LITERAL_CODES + NUM_LENGTH_CODES, NUM_LITERAL_CODES,
        NUM_LITERAL_CODES, NUM_LITERAL_CODES, NUM_DISTANCE_CODES};

    private static final byte[][] DISTANCE_MAP = {
        {0, 1}, {1, 0}, {1, 1}, {-1, 1}, {0, 2}, {2, 0}, {1, 2}, {-1, 2}, {2, 1}, {-2, 1}, {2, 2}, {-2, 2},
        {0, 3}, {3, 0}, {1, 3}, {-1, 3}, {3, 1}, {-3, 1}, {2, 3}, {-2, 3}, {3, 2}, {-3, 2}, {0, 4}, {4, 0},
        {1, 4}, {-1, 4}, {4, 1}, {-4, 1}, {3, 3}, {-3, 3}, {2, 4}, {-2, 4}, {4, 2}, {-4, 2}, {0, 5}, {3, 4},
        {-3, 4}, {4, 3}, {-4, 3}, {5, 0}, {1, 5}, {-1, 5}, {5, 1}, {-5, 1}, {2, 5}, {-2, 5}, {5, 2}, {-5, 2},
        {4, 4}, {-4, 4}, {3, 5}, {-3, 5}, {5, 3}, {-5, 3}, {0, 6}, {6, 0}, {1, 6}, {-1, 6}, {6, 1}, {-6, 1},
        {2, 6}, {-2, 6}, {6, 2}, {-6, 2}, {4, 5}, {-4, 5}, {5, 4}, {-5, 4}, {3, 6}, {-3, 6}, {6, 3}, {-6, 3},
        {0, 7}, {7, 0}, {1, 7}, {-1, 7}, {5, 5}, {-5, 5}, {7, 1}, {-7, 1}, {4, 6}, {-4, 6}, {6, 4}, {-6, 4},
        {2, 7}, {-2, 7}, {7, 2}, {-7, 2}, {3, 7}, {-3, 7}, {7, 3}, {-7, 3}, {5, 6}, {-5, 6}, {6, 5}, {-6, 5},
        {8, 0}, {4, 7}, {-4, 7}, {7, 4}, {-7, 4}, {8, 1}, {8, 2}, {6, 6}, {-6, 6}, {8, 3}, {5, 7}, {-5, 7},
        {7, 5}, {-7, 5}, {8, 4}, {6, 7}, {-6, 7}, {7, 6}, {-7, 6}, {8, 5}, {7, 7}, {-7, 7}, {8, 6}, {8, 7}};

    private WebpLossless() {
    }

    public static boolean isWebp(byte[] bytes) {
        return bytes.length >= 16 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
    }

    public static @Nullable IconPixels decode(byte[] bytes) throws IOException {
        if (!isWebp(bytes)) {
            throw new IOException("not a WebP file");
        }
        int at = 12;
        while (at + 8 <= bytes.length) {
            String chunk = new String(bytes, at, 4, StandardCharsets.US_ASCII);
            int size = readInt(bytes, at + 4);
            int body = at + 8;
            if (chunk.equals("VP8L")) {
                return new Decoder(bytes, body, Math.min(size, bytes.length - body)).read();
            }
            if (chunk.equals("VP8 ")) {
                return null;
            }
            at = body + size + (size & 1);
        }
        throw new IOException("no image in the WebP file");
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8 | (bytes[at + 2] & 0xff) << 16
                | (bytes[at + 3] & 0xff) << 24;
    }

    private static final class PrefixCode {
        private final int[] symbolsByCode;
        private final int[] firstCode;
        private final int[] firstIndex;
        private final int maxLength;
        private final int single;

        PrefixCode(int[] lengths) {
            int max = 0;
            int count = 0;
            int only = -1;
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                if (lengths[symbol] > 0) {
                    max = Math.max(max, lengths[symbol]);
                    count++;
                    only = symbol;
                }
            }
            this.maxLength = max;
            this.single = count == 1 ? only : -1;
            int[] perLength = new int[max + 2];
            for (int length : lengths) {
                if (length > 0) {
                    perLength[length]++;
                }
            }
            this.firstCode = new int[max + 2];
            this.firstIndex = new int[max + 2];
            int code = 0;
            int index = 0;
            for (int length = 1; length <= max; length++) {
                this.firstCode[length] = code;
                this.firstIndex[length] = index;
                code = (code + perLength[length]) << 1;
                index += perLength[length];
            }
            this.firstIndex[max + 1] = index;
            this.symbolsByCode = new int[index];
            int[] next = this.firstIndex.clone();
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                if (lengths[symbol] > 0) {
                    this.symbolsByCode[next[lengths[symbol]]++] = symbol;
                }
            }
        }

        int read(BitReader bits) throws IOException {
            if (this.single >= 0) {
                return this.single;
            }
            int code = 0;
            for (int length = 1; length <= this.maxLength; length++) {
                code = (code << 1) | bits.read(1);
                int offset = code - this.firstCode[length];
                int count = this.firstIndex[length + 1] - this.firstIndex[length];
                if (offset >= 0 && offset < count) {
                    return this.symbolsByCode[this.firstIndex[length] + offset];
                }
            }
            throw new IOException("a prefix code read past its longest code");
        }
    }

    private static final class BitReader {
        private final byte[] bytes;
        private final int end;
        private int at;
        private int bit;

        BitReader(byte[] bytes, int at, int length) {
            this.bytes = bytes;
            this.at = at;
            this.end = at + length;
        }

        int read(int count) throws IOException {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (this.at >= this.end) {
                    throw new IOException("the image ends early");
                }
                value |= ((this.bytes[this.at] >> this.bit) & 1) << i;
                if (++this.bit == 8) {
                    this.bit = 0;
                    this.at++;
                }
            }
            return value;
        }
    }

    private static final class Transform {
        private final int kind;
        private final int bitsPerBlock;
        private final int[] data;
        private final int dataWidth;

        Transform(int kind, int bitsPerBlock, int[] data, int dataWidth) {
            this.kind = kind;
            this.bitsPerBlock = bitsPerBlock;
            this.data = data;
            this.dataWidth = dataWidth;
        }

        int getKind() {
            return this.kind;
        }

        int getBitsPerBlock() {
            return this.bitsPerBlock;
        }

        int[] getData() {
            return this.data;
        }

        int getDataWidth() {
            return this.dataWidth;
        }
    }

    private static final class Decoder {
        private final BitReader bits;
        private int width;
        private int height;

        Decoder(byte[] bytes, int at, int length) {
            this.bits = new BitReader(bytes, at, length);
        }

        IconPixels read() throws IOException {
            if (this.bits.read(8) != MAGIC) {
                throw new IOException("not a lossless WebP");
            }
            this.width = this.bits.read(14) + 1;
            this.height = this.bits.read(14) + 1;
            this.bits.read(1);
            if (this.bits.read(3) != 0) {
                throw new IOException("a lossless WebP of a version this reader does not know");
            }
            Transform[] transforms = new Transform[4];
            int count = 0;
            int seen = 0;
            int imageWidth = this.width;
            while (this.bits.read(1) == 1) {
                int kind = this.bits.read(2);
                if ((seen & (1 << kind)) != 0) {
                    throw new IOException("a transform repeated");
                }
                seen |= 1 << kind;
                transforms[count++] = switch (kind) {
                    case 0, 1 -> {
                        int blockBits = this.bits.read(3) + 2;
                        int blocksWide = countBlocks(imageWidth, blockBits);
                        int blocksHigh = countBlocks(this.height, blockBits);
                        yield new Transform(kind, blockBits, this.readImage(blocksWide, blocksHigh, false),
                                blocksWide);
                    }
                    case 2 -> new Transform(kind, 0, new int[0], 0);
                    default -> {
                        int colours = this.bits.read(8) + 1;
                        int[] palette = this.readImage(colours, 1, false);
                        for (int i = 1; i < colours; i++) {
                            palette[i] = addChannels(palette[i], palette[i - 1]);
                        }
                        int perByte = 0;
                        if (colours <= 2) {
                            perByte = 3;
                        } else if (colours <= 4) {
                            perByte = 2;
                        } else if (colours <= 16) {
                            perByte = 1;
                        }
                        imageWidth = countBlocks(imageWidth, perByte);
                        yield new Transform(kind, perByte, palette, colours);
                    }
                };
            }
            int[] pixels = this.readImage(imageWidth, this.height, true);
            for (int i = count - 1; i >= 0; i--) {
                pixels = this.takeOff(transforms[i], pixels, i == count - 1 ? imageWidth : this.width);
            }
            IconPixels made = IconPixels.of(this.width, this.height, pixels);
            if (made == null) {
                throw new IOException("the image has no pixels");
            }
            return made;
        }

        private static int countBlocks(int size, int bits) {
            return (size + (1 << bits) - 1) >> bits;
        }

        private static int addChannels(int a, int b) {
            int alpha = ((a >>> 24) + (b >>> 24)) & 0xff;
            int red = (((a >> 16) & 0xff) + ((b >> 16) & 0xff)) & 0xff;
            int green = (((a >> 8) & 0xff) + ((b >> 8) & 0xff)) & 0xff;
            int blue = ((a & 0xff) + (b & 0xff)) & 0xff;
            return alpha << 24 | red << 16 | green << 8 | blue;
        }

        private int[] readImage(int width, int height, boolean main) throws IOException {
            int cacheBits = 0;
            if (this.bits.read(1) == 1) {
                cacheBits = this.bits.read(4);
                if (cacheBits < 1 || cacheBits > 11) {
                    throw new IOException("a colour cache of " + cacheBits + " bits");
                }
            }
            int[] cache = cacheBits == 0 ? new int[0] : new int[1 << cacheBits];
            int metaBits = 0;
            int[] meta = new int[0];
            int metaWidth = 0;
            int groups = 1;
            if (main && this.bits.read(1) == 1) {
                metaBits = this.bits.read(3) + 2;
                metaWidth = countBlocks(width, metaBits);
                meta = this.readImage(metaWidth, countBlocks(height, metaBits), false);
                for (int i = 0; i < meta.length; i++) {
                    meta[i] = (meta[i] >> 8) & 0xffff;
                    groups = Math.max(groups, meta[i] + 1);
                }
            }
            PrefixCode[][] codes = new PrefixCode[groups][5];
            for (int group = 0; group < groups; group++) {
                for (int i = 0; i < 5; i++) {
                    int size = GROUP_SIZES[i] + (i == 0 ? (cacheBits == 0 ? 0 : 1 << cacheBits) : 0);
                    codes[group][i] = this.readPrefixCode(size);
                }
            }
            int[] pixels = new int[width * height];
            int at = 0;
            int total = pixels.length;
            while (at < total) {
                int x = at % width;
                int y = at / width;
                PrefixCode[] group = codes[metaBits == 0 ? 0 : meta[(y >> metaBits) * metaWidth + (x >> metaBits)]];
                int symbol = group[0].read(this.bits);
                if (symbol < NUM_LITERAL_CODES) {
                    int red = group[1].read(this.bits);
                    int blue = group[2].read(this.bits);
                    int alpha = group[3].read(this.bits);
                    int pixel = alpha << 24 | red << 16 | symbol << 8 | blue;
                    pixels[at++] = pixel;
                    if (cacheBits > 0) {
                        cache[cacheIndex(pixel, cacheBits)] = pixel;
                    }
                } else if (symbol < NUM_LITERAL_CODES + NUM_LENGTH_CODES) {
                    int length = this.readLengthOrDistance(symbol - NUM_LITERAL_CODES);
                    int distanceCode = this.readLengthOrDistance(group[4].read(this.bits));
                    int distance = findDistance(distanceCode, width);
                    if (distance > at || at + length > total) {
                        throw new IOException("a back-reference outside the image");
                    }
                    for (int i = 0; i < length; i++) {
                        int pixel = pixels[at - distance];
                        pixels[at++] = pixel;
                        if (cacheBits > 0) {
                            cache[cacheIndex(pixel, cacheBits)] = pixel;
                        }
                    }
                } else {
                    int pixel = cache[symbol - NUM_LITERAL_CODES - NUM_LENGTH_CODES];
                    pixels[at++] = pixel;
                    if (cacheBits > 0) {
                        cache[cacheIndex(pixel, cacheBits)] = pixel;
                    }
                }
            }
            return pixels;
        }

        private static int cacheIndex(int pixel, int cacheBits) {
            return (int) ((0x1e35a7bdL * (pixel & 0xffffffffL)) >>> (32 - cacheBits)) & ((1 << cacheBits) - 1);
        }

        private int readLengthOrDistance(int symbol) throws IOException {
            if (symbol < 4) {
                return symbol + 1;
            }
            int extraBits = (symbol - 2) >> 1;
            int offset = (2 + (symbol & 1)) << extraBits;
            return offset + this.bits.read(extraBits) + 1;
        }

        private static int findDistance(int code, int width) {
            if (code > 120) {
                return code - 120;
            }
            byte[] offset = DISTANCE_MAP[code - 1];
            int distance = offset[0] + offset[1] * width;
            return Math.max(distance, 1);
        }

        private PrefixCode readPrefixCode(int alphabet) throws IOException {
            int[] lengths = new int[alphabet];
            if (this.bits.read(1) == 1) {
                int symbols = this.bits.read(1) + 1;
                int firstBits = this.bits.read(1) == 1 ? 8 : 1;
                int first = this.bits.read(firstBits);
                lengths[first] = 1;
                if (symbols == 2) {
                    lengths[this.bits.read(8)] = 1;
                }
                return new PrefixCode(lengths);
            }
            int[] codeLengthLengths = new int[CODE_LENGTH_CODES];
            int count = this.bits.read(4) + 4;
            for (int i = 0; i < count; i++) {
                codeLengthLengths[CODE_LENGTH_ORDER[i]] = this.bits.read(3);
            }
            PrefixCode codeLengths = new PrefixCode(codeLengthLengths);
            int toRead = alphabet;
            if (this.bits.read(1) == 1) {
                int lengthBits = 2 + 2 * this.bits.read(3);
                toRead = 2 + this.bits.read(lengthBits);
            }
            int previous = 8;
            int symbol = 0;
            while (symbol < alphabet && toRead-- > 0) {
                int code = codeLengths.read(this.bits);
                if (code < 16) {
                    lengths[symbol++] = code;
                    if (code != 0) {
                        previous = code;
                    }
                } else {
                    int repeat;
                    int value;
                    switch (code) {
                        case 16 -> {
                            repeat = 3 + this.bits.read(2);
                            value = previous;
                        }
                        case 17 -> {
                            repeat = 3 + this.bits.read(3);
                            value = 0;
                        }
                        default -> {
                            repeat = 11 + this.bits.read(7);
                            value = 0;
                        }
                    }
                    if (symbol + repeat > alphabet) {
                        throw new IOException("code lengths past the alphabet");
                    }
                    Arrays.fill(lengths, symbol, symbol + repeat, value);
                    symbol += repeat;
                }
            }
            return new PrefixCode(lengths);
        }

        private int[] takeOff(Transform transform, int[] pixels, int width) {
            return switch (transform.getKind()) {
                case 0 -> this.takePredictionOff(transform, pixels);
                case 1 -> this.takeColourOff(transform, pixels);
                case 2 -> takeSubtractGreenOff(pixels);
                default -> this.takeIndexingOff(transform, pixels, width);
            };
        }

        private int[] takePredictionOff(Transform transform, int[] pixels) {
            int w = this.width;
            int h = this.height;
            int blockBits = transform.getBitsPerBlock();
            int[] out = pixels.clone();
            out[0] = addChannels(pixels[0], 0xff000000);
            for (int x = 1; x < w; x++) {
                out[x] = addChannels(pixels[x], out[x - 1]);
            }
            for (int y = 1; y < h; y++) {
                int row = y * w;
                out[row] = addChannels(pixels[row], out[row - w]);
                for (int x = 1; x < w; x++) {
                    int block = (y >> blockBits) * transform.getDataWidth() + (x >> blockBits);
                    int mode = (transform.getData()[block] >> 8) & 0xf;
                    int at = row + x;
                    int left = out[at - 1];
                    int top = out[at - w];
                    int topLeft = out[at - w - 1];
                    int topRight = x + 1 < w ? out[at - w + 1] : out[row];
                    out[at] = addChannels(pixels[at], findPrediction(mode, left, top, topLeft, topRight));
                }
            }
            return out;
        }

        private static int findPrediction(int mode, int left, int top, int topLeft, int topRight) {
            return switch (mode) {
                case 0 -> 0xff000000;
                case 1 -> left;
                case 2 -> top;
                case 3 -> topRight;
                case 4 -> topLeft;
                case 5 -> findAverage(findAverage(left, topRight), top);
                case 6 -> findAverage(left, topLeft);
                case 7 -> findAverage(left, top);
                case 8 -> findAverage(topLeft, top);
                case 9 -> findAverage(top, topRight);
                case 10 -> findAverage(findAverage(left, topLeft), findAverage(top, topRight));
                case 11 -> select(left, top, topLeft);
                case 12 -> clampAddSubtractFull(left, top, topLeft);
                case 13 -> clampAddSubtractHalf(findAverage(left, top), topLeft);
                default -> 0xff000000;
            };
        }

        private static int findAverage(int a, int b) {
            return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
        }

        private static int select(int left, int top, int topLeft) {
            int predictAlpha = (left >>> 24) + (top >>> 24) - (topLeft >>> 24);
            int predictRed = ((left >> 16) & 0xff) + ((top >> 16) & 0xff) - ((topLeft >> 16) & 0xff);
            int predictGreen = ((left >> 8) & 0xff) + ((top >> 8) & 0xff) - ((topLeft >> 8) & 0xff);
            int predictBlue = (left & 0xff) + (top & 0xff) - (topLeft & 0xff);
            int toLeft = Math.abs(predictAlpha - (left >>> 24)) + Math.abs(predictRed - ((left >> 16) & 0xff))
                    + Math.abs(predictGreen - ((left >> 8) & 0xff)) + Math.abs(predictBlue - (left & 0xff));
            int toTop = Math.abs(predictAlpha - (top >>> 24)) + Math.abs(predictRed - ((top >> 16) & 0xff))
                    + Math.abs(predictGreen - ((top >> 8) & 0xff)) + Math.abs(predictBlue - (top & 0xff));
            return toLeft < toTop ? left : top;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private static int clampAddSubtractFull(int a, int b, int c) {
            return clamp((a >>> 24) + (b >>> 24) - (c >>> 24)) << 24
                    | clamp(((a >> 16) & 0xff) + ((b >> 16) & 0xff) - ((c >> 16) & 0xff)) << 16
                    | clamp(((a >> 8) & 0xff) + ((b >> 8) & 0xff) - ((c >> 8) & 0xff)) << 8
                    | clamp((a & 0xff) + (b & 0xff) - (c & 0xff));
        }

        private static int clampAddSubtractHalf(int a, int b) {
            return clamp((a >>> 24) + ((a >>> 24) - (b >>> 24)) / 2) << 24
                    | clamp(((a >> 16) & 0xff) + (((a >> 16) & 0xff) - ((b >> 16) & 0xff)) / 2) << 16
                    | clamp(((a >> 8) & 0xff) + (((a >> 8) & 0xff) - ((b >> 8) & 0xff)) / 2) << 8
                    | clamp((a & 0xff) + ((a & 0xff) - (b & 0xff)) / 2);
        }

        private int[] takeColourOff(Transform transform, int[] pixels) {
            int w = this.width;
            int blockBits = transform.getBitsPerBlock();
            int[] out = new int[pixels.length];
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < w; x++) {
                    int element = transform.getData()[(y >> blockBits) * transform.getDataWidth() + (x >> blockBits)];
                    int greenToRed = (byte) (element & 0xff);
                    int greenToBlue = (byte) ((element >> 8) & 0xff);
                    int redToBlue = (byte) ((element >> 16) & 0xff);
                    int pixel = pixels[y * w + x];
                    int green = (byte) ((pixel >> 8) & 0xff);
                    int red = ((pixel >> 16) & 0xff) + ((greenToRed * green) >> 5);
                    int blue = (pixel & 0xff) + ((greenToBlue * green) >> 5) + ((redToBlue * (byte) red) >> 5);
                    out[y * w + x] = (pixel & 0xff00ff00) | (red & 0xff) << 16 | (blue & 0xff);
                }
            }
            return out;
        }

        private static int[] takeSubtractGreenOff(int[] pixels) {
            int[] out = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int green = (pixel >> 8) & 0xff;
                int red = (((pixel >> 16) & 0xff) + green) & 0xff;
                int blue = ((pixel & 0xff) + green) & 0xff;
                out[i] = (pixel & 0xff00ff00) | red << 16 | blue;
            }
            return out;
        }

        private int[] takeIndexingOff(Transform transform, int[] pixels, int packedWidth) {
            int perByte = transform.getBitsPerBlock();
            int[] palette = transform.getData();
            int[] out = new int[this.width * this.height];
            int perPixel = 8 >> perByte;
            int mask = (1 << perPixel) - 1;
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    int packed = pixels[y * packedWidth + (x >> perByte)];
                    int index = ((packed >> 8) >> ((x & ((1 << perByte) - 1)) * perPixel)) & mask;
                    out[y * this.width + x] = index < palette.length ? palette[index] : 0;
                }
            }
            return out;
        }
    }
}
