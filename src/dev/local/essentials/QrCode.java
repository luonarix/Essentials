package dev.local.essentials;

import java.nio.charset.Charset;

/**
 * Minimal QR Code encoder: byte mode, error correction level M, versions 1-10.
 *
 * <p>Everything is generated locally with the arithmetic from ISO/IEC 18004
 * (Reed-Solomon over GF(256), mask patterns and penalty scoring), so no
 * network access or third-party library is needed.</p>
 */
final class QrCode {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Total data codewords for versions 1..10 (index 0 unused). */
    private static final int[] DATA_CODEWORDS = {
            0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216
    };

    /** Error correction codewords per block, level M. */
    private static final int[] ECC_PER_BLOCK = {
            0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26
    };

    /** Number of Reed-Solomon blocks, level M. */
    private static final int[] BLOCKS = {
            0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5
    };

    /** Alignment pattern centre coordinates, versions 2..10. */
    private static final int[][] ALIGN = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}
    };

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < EXP.length; i++) EXP[i] = EXP[i - 255];
    }

    private QrCode() {}

    /** Largest payload accepted in byte mode at version 10 / level M. */
    static int maxBytes() {
        return DATA_CODEWORDS[10] - 3;
    }

    static boolean fits(String text) {
        return text != null && text.getBytes(UTF8).length <= maxBytes();
    }

    static boolean[][] encode(String text) {
        return encode(text, -1);
    }

    /**
     * @param forcedMask 0..7 to force a mask, or -1 to pick the best one by
     *                   the standard penalty rules.
     */
    static boolean[][] encode(String text, int forcedMask) {
        byte[] payload = text == null ? new byte[0] : text.getBytes(UTF8);
        int version = versionFor(payload.length);
        if (version < 0) throw new IllegalArgumentException("Dữ liệu quá dài");

        boolean[] data = buildFinalBits(buildDataBits(payload, version), version);
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];
        int[] align = ALIGN[version];

        drawFunctionPatterns(modules, function, version, align);

        int bestMask = 0;
        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        int first = forcedMask >= 0 ? forcedMask : 0;
        int last = forcedMask >= 0 ? forcedMask : 7;
        for (int mask = first; mask <= last; mask++) {
            boolean[][] trial = new boolean[size][size];
            for (int y = 0; y < size; y++) {
                System.arraycopy(modules[y], 0, trial[y], 0, size);
            }
            placeData(trial, function, data);
            applyMask(trial, function, mask);
            drawFormatBits(trial, function, mask, size);
            int penalty = penalty(trial);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
                best = trial;
            }
        }
        if (best == null) return modules;
        if (forcedMask < 0) {
            // best already carries the right format bits for bestMask.
            return best;
        }
        drawFormatBits(best, function, bestMask, size);
        return best;
    }

    private static int versionFor(int length) {
        for (int v = 1; v <= 10; v++) {
            int countBits = v <= 9 ? 8 : 16;
            if (4 + countBits + length * 8 <= DATA_CODEWORDS[v] * 8) return v;
        }
        return -1;
    }

    // ------------------------------------------------------------ data bits

    private static boolean[] buildDataBits(byte[] payload, int version) {
        int capacityBits = DATA_CODEWORDS[version] * 8;
        boolean[] bits = new boolean[capacityBits];
        int n = 0;
        n = append(bits, n, 0x4, 4); // byte mode
        int countBits = version <= 9 ? 8 : 16;
        n = append(bits, n, payload.length, countBits);
        for (int i = 0; i < payload.length; i++) {
            n = append(bits, n, payload[i] & 0xFF, 8);
        }
        int terminator = Math.min(4, capacityBits - n);
        n = append(bits, n, 0, terminator);
        while (n % 8 != 0) bits[n++] = false;

        int pad = 0;
        while (n < capacityBits) {
            n = append(bits, n, (pad++ % 2 == 0) ? 0xEC : 0x11, 8);
        }
        return bits;
    }

    private static int append(boolean[] bits, int n, int value, int len) {
        for (int i = len - 1; i >= 0; i--) {
            bits[n++] = ((value >>> i) & 1) != 0;
        }
        return n;
    }

    // ------------------------------------------------------------ codewords

    private static int[] toCodewords(boolean[] bits) {
        int[] out = new int[bits.length / 8];
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bits[i * 8 + j] ? 1 : 0);
            }
            out[i] = b;
        }
        return out;
    }

    /** Splits data, appends Reed-Solomon blocks and interleaves them. */
    private static boolean[] buildFinalBits(boolean[] dataBits, int version) {
        int[] data = toCodewords(dataBits);
        int blocks = BLOCKS[version];
        int ecc = ECC_PER_BLOCK[version];
        int total = DATA_CODEWORDS[version];
        int shortLen = total / blocks;
        int numShort = blocks - total % blocks;

        int[][] dataBlocks = new int[blocks][];
        int[][] eccBlocks = new int[blocks][];
        int pos = 0;
        for (int b = 0; b < blocks; b++) {
            int len = shortLen + (b < numShort ? 0 : 1);
            int[] block = new int[len];
            System.arraycopy(data, pos, block, 0, len);
            pos += len;
            dataBlocks[b] = block;
            eccBlocks[b] = rsRemainder(block, ecc);
        }

        boolean[] out = new boolean[(total + ecc * blocks) * 8];
        int n = 0;
        for (int i = 0; i <= shortLen; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dataBlocks[b].length) {
                    n = append(out, n, dataBlocks[b][i], 8);
                }
            }
        }
        for (int i = 0; i < ecc; i++) {
            for (int b = 0; b < blocks; b++) {
                n = append(out, n, eccBlocks[b][i], 8);
            }
        }
        return out;
    }

    private static int[] rsRemainder(int[] block, int degree) {
        int[] gen = generator(degree);
        int[] res = new int[degree];
        for (int k = 0; k < block.length; k++) {
            int factor = (block[k] ^ res[0]) & 0xFF;
            System.arraycopy(res, 1, res, 0, degree - 1);
            res[degree - 1] = 0;
            if (factor != 0) {
                for (int i = 0; i < degree; i++) {
                    res[i] ^= mul(gen[i + 1], factor);
                }
            }
        }
        return res;
    }

    private static int[] generator(int degree) {
        int[] g = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[g.length + 1];
            int root = EXP[i];
            for (int j = 0; j < g.length; j++) {
                next[j] ^= g[j];
                next[j + 1] ^= mul(g[j], root);
            }
            g = next;
        }
        return g;
    }

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    // ------------------------------------------------------------ layout

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] f,
                                             int version, int[] align) {
        int size = m.length;

        // Timing patterns.
        for (int i = 0; i < size; i++) {
            set(m, f, 6, i, i % 2 == 0);
            set(m, f, i, 6, i % 2 == 0);
        }

        // Finder patterns with their separators.
        drawFinder(m, f, 3, 3);
        drawFinder(m, f, size - 4, 3);
        drawFinder(m, f, 3, size - 4);

        // Alignment patterns, skipping the three finder corners.
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                boolean corner = (i == 0 && j == 0)
                        || (i == 0 && j == align.length - 1)
                        || (i == align.length - 1 && j == 0);
                if (corner) continue;
                drawAlign(m, f, align[i], align[j]);
            }
        }

        // Reserve the format information strips.
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                set(m, f, 8, i, false);
                set(m, f, i, 8, false);
            }
        }
        for (int i = 0; i < 8; i++) {
            set(m, f, size - 1 - i, 8, false);
            set(m, f, 8, size - 1 - i, false);
        }
        // Dark module.
        set(m, f, 8, size - 8, true);

        // Version information (versions 7 and up).
        if (version >= 7) {
            int bits = versionInfo(version);
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3;
                int b = i / 3;
                set(m, f, a, b, bit);
                set(m, f, b, a, bit);
            }
        }
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int cx, int cy) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(m, f, x, y, d != 2 && d != 4);
            }
        }
    }

    private static void drawAlign(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(m, f, cx + dx, cy + dy, d != 1);
            }
        }
    }

    private static void placeData(boolean[][] m, boolean[][] f, boolean[] bits) {
        int size = m.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < bits.length) {
                        m[y][x] = bits[i++];
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!f[y][x] && maskBit(mask, x, y)) m[y][x] = !m[y][x];
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            case 7: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
            default: return false;
        }
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] f, int mask, int size) {
        int data = mask; // level M is 00b
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) set(m, f, 8, i, get(bits, i));
        set(m, f, 8, 7, get(bits, 6));
        set(m, f, 8, 8, get(bits, 7));
        set(m, f, 7, 8, get(bits, 8));
        for (int i = 9; i < 15; i++) set(m, f, 14 - i, 8, get(bits, i));

        for (int i = 0; i < 8; i++) set(m, f, size - 1 - i, 8, get(bits, i));
        for (int i = 8; i < 15; i++) set(m, f, 8, size - 15 + i, get(bits, i));
        set(m, f, 8, size - 8, true);
    }

    private static int versionInfo(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        return (version << 12) | rem;
    }

    private static boolean get(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static void set(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    // ------------------------------------------------------------ mask score

    private static int penalty(boolean[][] m) {
        int size = m.length;
        int score = 0;

        for (int y = 0; y < size; y++) {
            score += linePenalty(m[y]);
            boolean[] col = new boolean[size];
            for (int x = 0; x < size; x++) col[x] = m[x][y];
            score += linePenalty(col);
        }

        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }

        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) if (m[y][x]) dark++;
        }
        int percent = dark * 100 / (size * size);
        int low = percent - (percent % 5);
        int high = low + 5;
        score += Math.min(Math.abs(low - 50), Math.abs(high - 50)) / 5 * 10;
        return score;
    }

    private static int linePenalty(boolean[] line) {
        int score = 0;
        int run = 1;
        for (int i = 1; i < line.length; i++) {
            if (line[i] == line[i - 1]) {
                run++;
            } else {
                if (run >= 5) score += 3 + (run - 5);
                run = 1;
            }
        }
        if (run >= 5) score += 3 + (run - 5);

        // Finder-like 1:1:3:1:1 with four light modules on either side.
        for (int i = 0; i + 10 < line.length; i++) {
            if (line[i] && !line[i + 1] && line[i + 2] && line[i + 3]
                    && line[i + 4] && !line[i + 5] && line[i + 6]
                    && !line[i + 7] && !line[i + 8] && !line[i + 9]
                    && !line[i + 10]) {
                score += 40;
            }
            if (!line[i] && !line[i + 1] && !line[i + 2] && !line[i + 3]
                    && line[i + 4] && !line[i + 5] && line[i + 6]
                    && line[i + 7] && line[i + 8] && !line[i + 9]
                    && line[i + 10]) {
                score += 40;
            }
        }
        return score;
    }
}
