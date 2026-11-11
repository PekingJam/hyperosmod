package com.hyperosmod.singbox;

import android.text.Editable;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;

/**
 * 配置文件编辑器的语法高亮与格式化。
 *
 * <p>高亮用一次扫描完成（不是把几个正则叠在一起）：叠正则时数字规则会命中字符串内部的
 * 数字，键的着色也会被后面的字符串规则盖掉，最后键和值同色。扫描器把每个 token 的类型
 * 一次定死，span 之间不重叠，颜色就不会互相污染。</p>
 */
public final class JsonHighlighter {

    /** 各 token 的配色。调用方按明暗主题传入。 */
    public static final class Palette {
        public final int key;
        public final int string;
        public final int number;
        public final int url;
        public final int literal;

        public Palette(int key, int string, int number, int url, int literal) {
            this.key = key;
            this.string = string;
            this.number = number;
            this.url = url;
            this.literal = literal;
        }
    }

    private JsonHighlighter() {
    }

    public static void apply(Editable text, Palette palette) {
        if (text == null) {
            return;
        }
        for (ForegroundColorSpan span : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = scanString(text, i);
                boolean key = isFollowedByColon(text, end);
                boolean url = !key && looksLikeUrl(text, i, end);
                int color = key ? palette.key : (url ? palette.url : palette.string);
                text.setSpan(new ForegroundColorSpan(color), i, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
                continue;
            }
            if (c == '-' || Character.isDigit(c)) {
                int end = scanNumber(text, i);
                if (end > i) {
                    text.setSpan(new ForegroundColorSpan(palette.number), i, end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end;
                    continue;
                }
            }
            if (Character.isLetter(c)) {
                int end = i;
                while (end < length && Character.isLetter(text.charAt(end))) {
                    end++;
                }
                String word = text.subSequence(i, end).toString();
                if (word.equals("true") || word.equals("false") || word.equals("null")) {
                    text.setSpan(new ForegroundColorSpan(palette.literal), i, end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                i = end;
                continue;
            }
            i++;
        }
    }

    /** 从引号开始扫到配对的收尾引号（包含收尾引号），转义字符不算结束。 */
    private static int scanString(CharSequence text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    /** 字符串后面（跳过空白）是冒号，说明它是键。 */
    private static boolean isFollowedByColon(CharSequence text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == ':';
    }

    private static boolean looksLikeUrl(CharSequence text, int start, int end) {
        int contentStart = start + 1;
        if (end - contentStart < 8) {
            return false;
        }
        String head = text.subSequence(contentStart, Math.min(end, contentStart + 8)).toString();
        return head.startsWith("http://") || head.startsWith("https://");
    }

    private static int scanNumber(CharSequence text, int start) {
        int i = start;
        if (i < text.length() && text.charAt(i) == '-') {
            i++;
        }
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    /** 取光标所在的单词，用于补全与拼写检查。 */
    public static String tokenAt(CharSequence text, int cursor) {
        if (text == null || cursor < 0 || cursor > text.length()) {
            return "";
        }
        int start = tokenStart(text, cursor);
        int end = cursor;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return start == end ? "" : text.subSequence(start, end).toString();
    }

    /** 光标前单词的起始位置，用于「补全后替换」。 */
    public static int tokenStart(CharSequence text, int cursor) {
        int start = cursor;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    /**
     * 格式化 JSON：两空格缩进，字符串和转义原样保留。
     *
     * <p>不用 JSON 库，是为了在语法有错时也能把半成品格式化出来看结构。</p>
     */
    public static String format(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + raw.length() / 4);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c);
                    indent++;
                    newline(out, indent);
                    break;
                case '}':
                case ']':
                    indent = Math.max(0, indent - 1);
                    newline(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    newline(out, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString().trim();
    }

    /** 压成一行：去掉字符串外的所有空白。 */
    public static String minify(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void newline(StringBuilder out, int indent) {
        out.append('\n');
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }
}
