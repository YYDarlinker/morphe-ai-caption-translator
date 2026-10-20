package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The saved settings of the translator. What belongs to a profile (endpoint, model, prompt) is read
 * from the store of the active profile; everything else is global.
 */
final class DeepSeekConfig {
    private static final String PREFS = "deepseek_caption_translator";
    private static final String ENABLED = "enabled";
    private static final String DISPLAY_TEXT_DEBUG = "display_text_debug";
    private static final String BASE_URL = "base_url";
    private static final String MODEL = "model";
    private static final String PROMPT = "prompt";
    private static final String CAPTION_SIZE_TIER = "caption_size_tier";

    /** Only the setting just before the size tiers is upgraded; older ones used other units. */
    private static final String GLYPH_HEIGHT_RATIO_BPS = "caption_glyph_height_ratio_bps";

    private static final String BACKGROUND_OPACITY = "background_opacity";
    private static final String POSITION_PORTRAIT_Y = "position_portrait_y";
    private static final String POSITION_LANDSCAPE_Y = "position_landscape_y";
    private static final String POSITION_SHORTS_Y = "shorts_y";
    private static final String FLYOUT_MENU = "flyout_menu";
    private static final String SHORTS_FLYOUT_MENU = "shorts_flyout_menu";

    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String DEFAULT_MODEL = "deepseek-v4-flash";
    static final String DEFAULT_PROMPT =
            "忠实、自然、简洁；优先符合目标语言的母语表达习惯；保留人名、专有名词、数字、语气和必要的标点；不要增加原文没有的解释。";

    private static final String LEGACY_CHINESE_PROMPT =
            "忠实、自然、简洁地翻译成简体中文；优先符合中文表达习惯；保留人名、专有名词、数字、语气和必要的标点；不要增加原文没有的解释。";
    private static final String ENGLISH_PROMPT =
            "Translate faithfully, naturally and concisely into the target language, using"
                    + " native phrasing. Preserve names, terminology, numbers, tone and necessary"
                    + " punctuation. Do not add explanations absent from the source.";
    private static final int DEFAULT_BACKGROUND_OPACITY = 70;

    private DeepSeekConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences profile(Context context) {
        return ApiProfiles.values(context);
    }

    // ---- Switches ----

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static void saveEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply();
    }

    static boolean flyoutMenuEnabled(Context context) {
        return prefs(context).getBoolean(FLYOUT_MENU, true);
    }

    static void saveFlyoutMenuEnabled(Context context, boolean visible) {
        prefs(context).edit().putBoolean(FLYOUT_MENU, visible).apply();
    }

    static boolean shortsFlyoutMenuEnabled(Context context) {
        return prefs(context).getBoolean(SHORTS_FLYOUT_MENU, true);
    }

    static void saveShortsFlyoutMenuEnabled(Context context, boolean visible) {
        prefs(context).edit().putBoolean(SHORTS_FLYOUT_MENU, visible).apply();
    }

    static boolean displayTextDebugEnabled(Context context) {
        return prefs(context).getBoolean(DISPLAY_TEXT_DEBUG, false);
    }

    static void saveDisplayTextDebugEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(DISPLAY_TEXT_DEBUG, enabled).apply();
        if (!enabled) CaptionQualityTrace.clear(context);
    }

    // ---- Provider: endpoint, model, prompt, key ----

    /** The settings a request is made with; they hold no plaintext key. */
    static ProviderSettings load(Context context) {
        synchronized (ApiProfiles.LOCK) {
            SharedPreferences profile = profile(context);
            String prompt = profile.getString(PROMPT, "");
            // No prompt is a default that follows the language, not a text frozen into the profile.
            boolean programDefault = isDefaultPrompt(prompt);
            return new ProviderSettings(
                    enabled(context),
                    orDefault(profile.getString(BASE_URL, DEFAULT_BASE_URL), DEFAULT_BASE_URL),
                    profile.getString(MODEL, DEFAULT_MODEL),
                    programDefault ? defaultPrompt(context) : prompt,
                    CaptionCredentialRef.of(SecureApiKey.load(context)),
                    programDefault ? "program_default" : "stored_custom");
        }
    }

    static boolean isReady(Context context) {
        return load(context).ready();
    }

    private static boolean isDefaultPrompt(String prompt) {
        return prompt == null
                || prompt.trim().isEmpty()
                || prompt.equals(LEGACY_CHINESE_PROMPT)
                || prompt.equals(DEFAULT_PROMPT);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static void saveBaseUrl(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.startsWith("https://") && !clean.startsWith("http://"))
            throw new IllegalArgumentException("API 地址必须以 https:// 或 http:// 开头");
        synchronized (ApiProfiles.LOCK) {
            String endpoint = ProviderEndpoint.validate(clean);
            SecureApiKey.bindLegacyOrigin(context);
            profile(context).edit().putString(BASE_URL, endpoint).apply();
        }
    }

    static void saveModel(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("模型不能为空");
        synchronized (ApiProfiles.LOCK) {
            profile(context).edit().putString(MODEL, clean).apply();
        }
    }

    /** A prompt equal to a default is not stored: it follows the default from then on. */
    static void savePrompt(Context context, String value) {
        String clean = value == null ? "" : value.trim();
        synchronized (ApiProfiles.LOCK) {
            SharedPreferences.Editor edit = profile(context).edit();
            if (clean.isEmpty() || clean.equals(defaultPrompt(context)) || isDefaultPrompt(clean))
                edit.remove(PROMPT);
            else edit.putString(PROMPT, clean);
            edit.apply();
        }
    }

    /**
     * The default prompt in the language of the app; the English one when the string resource is
     * not available. It is what a request carries, not what the UI shows.
     */
    static String defaultPrompt(Context context) {
        try {
            Object value =
                    Class.forName("app.morphe.extension.shared.ResourceUtils")
                            .getMethod("getString", String.class)
                            .invoke(null, "cap_default_prompt");
            if (value instanceof String && !value.equals("cap_default_prompt"))
                return (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // The resources of the app.
        }
        if (context != null) {
            int id =
                    context.getResources()
                            .getIdentifier(
                                    "cap_default_prompt", "string", context.getPackageName());
            if (id != 0) return context.getString(id);
        }
        return ENGLISH_PROMPT;
    }

    // ---- Look and position of the captions ----

    static Appearance displayStyle(Context context) {
        SharedPreferences prefs = prefs(context);
        return new Appearance(
                captionSizeTier(prefs),
                clampOpacity(prefs.getInt(BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)));
    }

    /** The size tier; the ratio of an older release is converted to the nearest tier once. */
    private static int captionSizeTier(SharedPreferences prefs) {
        synchronized (prefs) {
            if (prefs.contains(CAPTION_SIZE_TIER))
                return CaptionFontSize.clampTier(
                        prefs.getInt(CAPTION_SIZE_TIER, CaptionFontSize.DEFAULT_TIER));
            if (!prefs.contains(GLYPH_HEIGHT_RATIO_BPS)) return CaptionFontSize.DEFAULT_TIER;
            float glyphHeightPx = prefs.getInt(GLYPH_HEIGHT_RATIO_BPS, 0) * 1264f / 10000f;
            int tier = CaptionFontSize.nearestTierForDetailGlyphHeight(glyphHeightPx);
            prefs.edit().putInt(CAPTION_SIZE_TIER, tier).apply();
            return tier;
        }
    }

    static void saveCaptionSizeTier(Context context, int value) {
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            prefs.edit().putInt(CAPTION_SIZE_TIER, CaptionFontSize.clampTier(value)).apply();
        }
    }

    static void saveBackgroundOpacity(Context context, int value) {
        prefs(context).edit().putInt(BACKGROUND_OPACITY, clampOpacity(value)).apply();
    }

    private static int clampOpacity(int value) {
        return Math.max(0, Math.min(100, value));
    }

    static float captionPositionY(Context context, boolean landscape) {
        return clampPosition(
                prefs(context)
                        .getFloat(
                                landscape ? POSITION_LANDSCAPE_Y : POSITION_PORTRAIT_Y,
                                landscape ? 0.80f : 0.82f));
    }

    static void saveCaptionPosition(Context context, boolean landscape, float y) {
        prefs(context)
                .edit()
                .putFloat(landscape ? POSITION_LANDSCAPE_Y : POSITION_PORTRAIT_Y, clampPosition(y))
                .apply();
    }

    static float shortsPosition(Context context) {
        return clampPosition(prefs(context).getFloat(POSITION_SHORTS_Y, .72f));
    }

    static void saveShortsPosition(Context context, float value) {
        prefs(context).edit().putFloat(POSITION_SHORTS_Y, clampPosition(value)).apply();
    }

    static void resetCaptionPositions(Context context) {
        prefs(context)
                .edit()
                .remove(POSITION_SHORTS_Y)
                .remove(POSITION_PORTRAIT_Y)
                .remove(POSITION_LANDSCAPE_Y)
                .apply();
    }

    private static float clampPosition(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0.5f;
        return Math.max(0.04f, Math.min(0.96f, value));
    }

    // ---- Values ----

    /** What a request needs of the provider settings; no size, opacity or plaintext key. */
    static final class ProviderSettings {
        final boolean enabled, hasCredential;
        final String baseUrl,
                model,
                prompt,
                preferenceProvenance,
                effectivePreference,
                credentialId;

        ProviderSettings(
                boolean enabled,
                String endpoint,
                String model,
                String prompt,
                CaptionCredentialRef credential,
                String provenance) {
            this.enabled = enabled;
            baseUrl = endpoint;
            this.model = model;
            this.prompt = prompt;
            preferenceProvenance = provenance;
            credentialId = credential.fingerprint;
            hasCredential = !credentialId.isEmpty();
            effectivePreference =
                    "program_default".equals(provenance)
                            ? "Translate faithfully, naturally and concisely in the selected target"
                                    + " language; preserve names, numbers, tone and necessary"
                                    + " punctuation; do not add explanations."
                            : prompt;
        }

        boolean ready() {
            return enabled && hasCredential && model != null && !model.trim().isEmpty();
        }

        String fingerprint() {
            return baseUrl + '\n' + model + '\n' + prompt;
        }
    }

    static final class Appearance {
        final int captionSizeTier, backgroundOpacity;

        Appearance(int tier, int opacity) {
            captionSizeTier = tier;
            backgroundOpacity = opacity;
        }
    }
}
