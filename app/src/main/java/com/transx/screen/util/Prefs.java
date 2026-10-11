package com.transx.screen.util;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String PREF_NAME = "transx_settings";
    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getGoogleApiKey() {
        return sp.getString("google_api_key", "");
    }

    public void setGoogleApiKey(String key) {
        sp.edit().putString("google_api_key", key != null ? key.trim() : "").apply();
    }

    public String getSourceLang() {
        return sp.getString("source_lang", "en");
    }

    public void setSourceLang(String lang) {
        sp.edit().putString("source_lang", lang).apply();
    }

    public String getTargetLang() {
        return sp.getString("target_lang", "vi");
    }

    public void setTargetLang(String lang) {
        sp.edit().putString("target_lang", lang).apply();
    }

    public String getAudioSource() {
        return sp.getString("audio_source", "internal"); // "internal" or "mic"
    }

    public void setAudioSource(String src) {
        sp.edit().putString("audio_source", src).apply();
    }

    public boolean isTtsEnabled() {
        return sp.getBoolean("enable_tts", true);
    }

    public void setTtsEnabled(boolean enabled) {
        sp.edit().putBoolean("enable_tts", enabled).apply();
    }

    public float getTtsSpeed() {
        return sp.getFloat("tts_speed", 1.0f);
    }

    public void setTtsSpeed(float speed) {
        sp.edit().putFloat("tts_speed", speed).apply();
    }

    public String getTtsVoice() {
        return sp.getString("tts_voice", "vi-VN-Neural2-A");
    }

    public void setTtsVoice(String voice) {
        sp.edit().putString("tts_voice", voice).apply();
    }
}
