package app.yydarlinker.deepseekcaptions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.preference.Preference;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * The API profiles: each has its own endpoint, model, prompt and key. A profile is known by a
 * random id, never by its name or by the host of its endpoint. The first profile is the one the
 * settings of older releases live in; it keeps its store and the id {@link #LEGACY}.
 */
final class ApiProfiles {
    static final Object LOCK = new Object();
    static final String LEGACY = "default";
    private static final String INDEX = "caption_api_profiles";
    private static final String NAMES = "names";
    private static final String ACTIVE = "active";
    private static final String LEGACY_DELETED = "legacy_deleted";
    private static final String LEGACY_STORE = "deepseek_caption_translator";
    private static final int MAX_PROFILES = 30, MAX_NAME = 60;

    private static final List<WeakReference<Editor>> editors = new ArrayList<>();
    private static boolean flushing;
    private static volatile long revision;

    private ApiProfiles() {}

    /** A part of the UI that edits the active profile and can write its edits back. */
    interface Editor {
        /** Writes the edits; false if they are invalid and the profile must not change. */
        boolean flushProfile();

        void profileChanged();
    }

    /** Counts every change of the active profile or of its credentials. */
    static long revision() {
        return revision;
    }

    static boolean flushing() {
        return flushing;
    }

    static void register(Editor editor) {
        synchronized (LOCK) {
            editors.removeIf(ref -> ref.get() == null || ref.get() == editor);
            editors.add(new WeakReference<>(editor));
        }
    }

    static void unregister(Editor editor) {
        synchronized (LOCK) {
            editors.removeIf(ref -> ref.get() == null || ref.get() == editor);
        }
    }

    /** The editors that are alive and not in a finished activity. */
    private static List<Editor> liveEditors() {
        List<Editor> live = new ArrayList<>();
        synchronized (LOCK) {
            for (WeakReference<Editor> ref : editors) {
                Editor editor = ref.get();
                if (editor != null && !inFinishedActivity(editor)) live.add(editor);
            }
        }
        return live;
    }

    private static boolean inFinishedActivity(Editor editor) {
        if (!(editor instanceof Preference)) return false;
        Context context = ((Preference) editor).getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                return activity.isFinishing() || activity.isDestroyed();
            }
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return false;
    }

    // ---- Index and stores ----

    private static SharedPreferences index(Context context) {
        return context.getSharedPreferences(INDEX, Context.MODE_PRIVATE);
    }

    static String active(Context context) {
        synchronized (LOCK) {
            return index(context).getString(ACTIVE, LEGACY);
        }
    }

    /** The store of one profile. */
    static SharedPreferences values(Context context, String id) {
        return context.getSharedPreferences(
                LEGACY.equals(id) ? LEGACY_STORE : "caption_profile_" + id, Context.MODE_PRIVATE);
    }

    static SharedPreferences values(Context context) {
        return values(context, active(context));
    }

    /** The names of the profiles other than the first, by id. */
    private static JSONObject names(Context context) {
        try {
            return new JSONObject(index(context).getString(NAMES, "{}"));
        } catch (JSONException invalid) {
            throw new IllegalStateException("Invalid saved API profiles", invalid);
        }
    }

    private static void saveNames(
            Context context, JSONObject names, boolean legacyDeleted, String active) {
        SharedPreferences.Editor edit = index(context).edit().putString(NAMES, names.toString());
        if (legacyDeleted) edit.putBoolean(LEGACY_DELETED, true);
        if (active != null) edit.putString(ACTIVE, active);
        edit.apply();
    }

    /** The profiles by id, in order, with their names. */
    static LinkedHashMap<String, String> list(Context context) {
        synchronized (LOCK) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            if (!index(context).getBoolean(LEGACY_DELETED, false))
                result.put(LEGACY, CaptionStrings.get(context, "profile_default"));
            JSONObject names = names(context);
            for (Iterator<String> ids = names.keys(); ids.hasNext(); ) {
                String id = ids.next();
                result.put(id, names.optString(id));
            }
            return result;
        }
    }

    private static String checkedName(Context context, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME)
            throw new IllegalArgumentException(CaptionStrings.get(context, "profile_name_error"));
        return clean;
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown API profile");
    }

    static void rename(Context context, String id, String name) {
        synchronized (LOCK) {
            String clean = checkedName(context, name);
            if (!list(context).containsKey(id)) throw unknown();
            try {
                saveNames(context, names(context).put(id, clean), false, null);
            } catch (JSONException invalid) {
                throw new IllegalStateException("Invalid API profiles", invalid);
            }
        }
    }

    /** A new profile with the endpoint [base]; it copies neither the key nor the model. */
    static String create(Context context, String name, String base) {
        synchronized (LOCK) {
            String clean = checkedName(context, name);
            if (list(context).size() >= MAX_PROFILES)
                throw new IllegalStateException(CaptionStrings.get(context, "profile_limit"));
            String endpoint = ProviderEndpoint.validate(base);
            String id = UUID.randomUUID().toString();
            values(context, id)
                    .edit()
                    .putString("base_url", endpoint)
                    .putString("model", "")
                    .apply();
            try {
                saveNames(context, names(context).put(id, clean), false, null);
            } catch (JSONException invalid) {
                throw new IllegalStateException("Invalid API profiles", invalid);
            }
            return id;
        }
    }

    // ---- Switching ----

    static boolean flushCurrent() {
        return flush(false);
    }

    static boolean flushExceptKey() {
        return flush(true);
    }

    /** Asks the editors to write their edits; stops at the first that refuses. */
    private static boolean flush(boolean skipKey) {
        flushing = true;
        try {
            for (Editor editor : liveEditors()) {
                boolean keyField =
                        editor instanceof Preference
                                && DeepSeekTextPreference.KEY_API_KEY.equals(
                                        ((Preference) editor).getKey());
                if (!(skipKey && keyField) && !editor.flushProfile()) return false;
            }
            return true;
        } finally {
            flushing = false;
        }
    }

    /** Makes [id] the active profile, once the edits of the old one are saved. */
    static boolean select(Context context, String id) {
        synchronized (LOCK) {
            if (!list(context).containsKey(id)) throw unknown();
            if (id.equals(active(context))) return true;
        }
        // The edits are written while the old profile is still active.
        if (!flushCurrent()) return false;
        synchronized (LOCK) {
            index(context).edit().putString(ACTIVE, id).apply();
        }
        changed(context);
        return true;
    }

    static void changed(Context context) {
        synchronized (LOCK) {
            revision++;
        }
        for (Editor editor : liveEditors()) editor.profileChanged();
        DeepSeekModelPreference.onCredentialsChanged(context);
        CaptionActions.refreshConfiguration(context);
    }

    // ---- Removing ----

    /**
     * Deletes one profile and its key, never the global settings. The first profile shares its
     * store with them, so only its own entries are removed.
     */
    static void delete(Context context, String id) {
        boolean wasActive;
        synchronized (LOCK) {
            LinkedHashMap<String, String> saved = list(context);
            if (!saved.containsKey(id)) throw unknown();
            if (saved.size() <= 1)
                throw new IllegalStateException(CaptionStrings.get(context, "profile_keep_one"));
            wasActive = id.equals(active(context));
            saved.remove(id);
            JSONObject names = names(context);
            names.remove(id);
            saveNames(
                    context,
                    names,
                    LEGACY.equals(id),
                    wasActive ? saved.keySet().iterator().next() : null);
            SecureApiKey.clear(context, id);
            SharedPreferences.Editor store = values(context, id).edit();
            if (LEGACY.equals(id))
                store.remove("base_url").remove("model").remove("prompt").remove("planner_model");
            else store.clear();
            store.apply();
        }
        // The edits of a deleted profile must not be written back into it.
        if (wasActive) changed(context);
    }

    /** Forgets the key of one profile. */
    static void clearKey(Context context, String id) {
        synchronized (LOCK) {
            // A confirmation that is out of date must not touch another profile.
            if (!list(context).containsKey(id)) return;
            SecureApiKey.clear(context, id);
        }
        if (id.equals(active(context))) changed(context);
    }
}
