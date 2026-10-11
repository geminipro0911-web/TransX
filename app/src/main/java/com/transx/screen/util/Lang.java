package com.transx.screen.util;

public class Lang {
    public final String name;
    public final String edgeCode;
    public final String googleSttCode;
    public final String googleTtsVoice;

    public Lang(String name, String edgeCode, String googleSttCode, String googleTtsVoice) {
        this.name = name;
        this.edgeCode = edgeCode;
        this.googleSttCode = googleSttCode;
        this.googleTtsVoice = googleTtsVoice;
    }

    @Override
    public String toString() {
        return name;
    }

    public static final Lang[] ALL = new Lang[]{
        new Lang("Tiếng Việt", "vi", "vi-VN", "vi-VN-Neural2-A"),
        new Lang("English (US)", "en", "en-US", "en-US-Neural2-F"),
        new Lang("Tiếng Trung (Giản thể)", "zh-Hans", "zh-CN", "cmn-CN-Wavenet-A"),
        new Lang("Tiếng Nhật (日本語)", "ja", "ja-JP", "ja-JP-Neural2-B"),
        new Lang("Tiếng Hàn (한국어)", "ko", "ko-KR", "ko-KR-Neural2-A"),
        new Lang("Tiếng Pháp (Français)", "fr", "fr-FR", "fr-FR-Neural2-A"),
        new Lang("Tiếng Đức (Deutsch)", "de", "de-DE", "de-DE-Neural2-B"),
        new Lang("Tiếng Tây Ban Nha (Español)", "es", "es-ES", "es-ES-Neural2-A"),
        new Lang("Tiếng Nga (Русский)", "ru", "ru-RU", "ru-RU-Neural2-A"),
        new Lang("Tự động phát hiện (Auto)", "", "auto", "")
    };

    public static Lang findByEdgeCode(String code) {
        if (code == null) return ALL[0];
        for (Lang l : ALL) {
            if (l.edgeCode.equalsIgnoreCase(code)) return l;
        }
        return ALL[0];
    }
}
