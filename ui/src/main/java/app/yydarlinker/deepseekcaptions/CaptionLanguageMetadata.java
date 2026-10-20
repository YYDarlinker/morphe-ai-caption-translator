package app.yydarlinker.deepseekcaptions;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Narrow protobuf wire adapter. Host message schema is checked by the patch before installing. */
public final class CaptionLanguageMetadata {
    private CaptionLanguageMetadata() {}

    public static byte[] addSimplified(byte[] original) {
        if (original == null || original.length > 1024 * 1024) return original;
        if (!CaptionAddonSupport.aiInstalled() && !CaptionAddonSupport.simplifiedInstalled())
            return original;
        try {
            List<Field> root = fields(original);
            List<byte[]> languages = new ArrayList<>();
            byte[] prototype = null;
            Set<String> present = new HashSet<>();
            Map<String, Integer> positions = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            for (Field f : root)
                if (f.number == 3 && f.wire == 2) {
                    String normalized = CaptionLanguageSelection.canonical(code(f.value));
                    positions.put(normalized, languages.size());
                    languages.add(f.value);
                    if (!normalized.isEmpty()) {
                        present.add(normalized);
                        labels.put(normalized, label(f.value));
                    }
                    if (prototype == null) prototype = f.value;
                }
            NativeCaptionBridge.observeLanguageMetadata(labels);
            if (prototype == null) return original;
            Collator collator = Collator.getInstance(LanguageMenuOrder.locale());
            boolean changed = false;
            for (String code : CaptionLanguageSelection.menuCodes()) {
                if (present.contains(code)) {
                    // A selected Chinese variant may be native with YouTube's redundant label.
                    // Keep every other byte of that entry and replace only display field 4.
                    if (!isChineseVariant(code)) continue;
                    Integer at = positions.get(code);
                    if (at == null) continue;
                    byte[] existing = languages.get(at);
                    if (!NativeCaptionBridge.redundantChineseLabel(code, label(existing))) continue;
                    String wanted = NativeCaptionBridge.translationLabel(code);
                    if (wanted.equals(label(existing))) continue;
                    languages.set(at, relabel(existing, code, wanted));
                    changed = true;
                    continue;
                }
                present.add(code);
                ByteArrayOutputStream entry = new ByteArrayOutputStream(),
                        name = new ByteArrayOutputStream();
                write(entry, 1, code.getBytes(StandardCharsets.UTF_8));
                String display = NativeCaptionBridge.translationLabel(code);
                write(name, 4, display.getBytes(StandardCharsets.UTF_8));
                write(entry, 2, name.toByteArray());
                for (Field f : fields(prototype))
                    if (f.number != 1 && f.number != 2) entry.write(f.raw);
                int at = languages.size();
                for (int i = 0; i < languages.size(); i++)
                    if (collator.compare(
                                    LanguageMenuOrder.sortLabel(display),
                                    LanguageMenuOrder.sortLabel(label(languages.get(i))))
                            < 0) {
                        at = i;
                        break;
                    }
                languages.add(at, entry.toByteArray());
                changed = true;
            }
            if (!changed) return original;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean wrote = false;
            for (Field f : root) {
                if (f.number == 3 && f.wire == 2) {
                    if (!wrote) {
                        for (byte[] item : languages) write(out, 3, item);
                        wrote = true;
                    }
                } else out.write(f.raw);
            }
            return out.toByteArray();
        } catch (Exception malformed) {
            return original;
        }
    }

    private static boolean isChineseVariant(String code) {
        String canonical = CaptionLanguageSelection.canonical(code);
        return "zh-Hans".equals(canonical) || "zh-Hant".equals(canonical);
    }

    /** Replace only the display name (2.4) of one entry, preserving code and every other byte. */
    private static byte[] relabel(byte[] entry, String code, String display) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean wroteCode = false, wroteName = false;
        for (Field f : fields(entry)) {
            if (f.number == 1 && f.wire == 2) {
                // Keep the native region/script spelling (for example zh-TW); only the label is
                // product-owned. Rewriting the code here would silently change track identity.
                out.write(f.raw);
                wroteCode = true;
            } else if (f.number == 2 && f.wire == 2) {
                ByteArrayOutputStream name = new ByteArrayOutputStream();
                boolean wroteDisplay = false;
                for (Field nf : fields(f.value)) {
                    if (nf.number == 4 && nf.wire == 2) {
                        write(name, 4, display.getBytes(StandardCharsets.UTF_8));
                        wroteDisplay = true;
                    } else name.write(nf.raw);
                }
                if (!wroteDisplay) write(name, 4, display.getBytes(StandardCharsets.UTF_8));
                write(out, 2, name.toByteArray());
                wroteName = true;
            } else out.write(f.raw);
        }
        if (!wroteCode) write(out, 1, code.getBytes(StandardCharsets.UTF_8));
        if (!wroteName) {
            ByteArrayOutputStream name = new ByteArrayOutputStream();
            write(name, 4, display.getBytes(StandardCharsets.UTF_8));
            write(out, 2, name.toByteArray());
        }
        return out.toByteArray();
    }

    private static String label(byte[] bytes) {
        for (Field f : fields(bytes))
            if (f.number == 2 && f.wire == 2)
                for (Field name : fields(f.value))
                    if (name.number == 4 && name.wire == 2)
                        return new String(name.value, StandardCharsets.UTF_8);
        return LanguageMenuOrder.label(code(bytes));
    }

    private static String code(byte[] bytes) {
        for (Field f : fields(bytes))
            if (f.number == 1 && f.wire == 2) return new String(f.value, StandardCharsets.UTF_8);
        return "";
    }

    static void write(ByteArrayOutputStream out, int number, byte[] value) {
        varint(out, (number << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void varint(ByteArrayOutputStream out, long n) {
        while ((n & ~127L) != 0) {
            out.write((int) (n & 127) | 128);
            n >>>= 7;
        }
        out.write((int) n);
    }

    private static long read(byte[] b, int[] p) {
        long n = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p[0] >= b.length) throw new IllegalArgumentException("truncated");
            int v = b[p[0]++] & 255;
            n |= (long) (v & 127) << shift;
            if ((v & 128) == 0) return n;
        }
        throw new IllegalArgumentException("varint overflow");
    }

    static List<Field> fields(byte[] b) {
        List<Field> result = new ArrayList<>();
        int[] p = {0};
        while (p[0] < b.length) {
            int begin = p[0];
            long key = read(b, p);
            int number = (int) (key >>> 3), wire = (int) (key & 7);
            if (number <= 0) throw new IllegalArgumentException("field zero");
            byte[] data = null;
            if (wire == 0) read(b, p);
            else if (wire == 1) p[0] += 8;
            else if (wire == 5) p[0] += 4;
            else if (wire == 2) {
                long length = read(b, p);
                if (length < 0 || length > b.length - p[0])
                    throw new IllegalArgumentException("length");
                int end = p[0] + (int) length;
                data = Arrays.copyOfRange(b, p[0], end);
                p[0] = end;
            } else throw new IllegalArgumentException("unsupported wire type");
            if (p[0] > b.length) throw new IllegalArgumentException("truncated fixed");
            result.add(new Field(number, wire, data, Arrays.copyOfRange(b, begin, p[0])));
        }
        return result;
    }

    static final class Field {
        final int number, wire;
        final byte[] value, raw;

        Field(int n, int w, byte[] v, byte[] r) {
            number = n;
            wire = w;
            value = v;
            raw = r;
        }
    }
}
