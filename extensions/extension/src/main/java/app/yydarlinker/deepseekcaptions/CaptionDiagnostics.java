package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import android.content.SharedPreferences;

/** Small on-device trace so subtitle failures can be diagnosed without adb/logcat. */
final class CaptionDiagnostics {
    private static final String PREFS = "deepseek_caption_diagnostics";
    private static final String STAGE = "stage";
    private static final String DETAIL = "detail";
    private static final String TIME = "time";
    private static final String HISTORY = "history";
    private static final int MAX_HISTORY = 8000;

    private CaptionDiagnostics() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void mark(Context context, String stage, String detail) {
        if (context == null) return;
        try {
            String cleanStage = sanitize(stage, 80);
            String cleanDetail = sanitize(detail, 260);
            long now = System.currentTimeMillis();
            SharedPreferences p = prefs(context);
            String old = p.getString(HISTORY, "");
            String line = now + " | " + cleanStage + (cleanDetail.isEmpty() ? "" : " | " + cleanDetail);
            String next = old == null || old.isEmpty() ? line : line + "\n" + old;
            if (next.length() > MAX_HISTORY) next = next.substring(0, MAX_HISTORY);
            p.edit()
                    .putString(STAGE, cleanStage)
                    .putString(DETAIL, cleanDetail)
                    .putLong(TIME, now)
                    .putString(HISTORY, next)
                    .apply();

        } catch (Throwable ignored) {
        }
    }

    static void clear(Context context) {
        try { prefs(context).edit().clear().apply(); } catch (Throwable ignored) {}
        try { TokenCostAudit.clear(context); } catch (Throwable ignored) {}
    }

    static String uiText(Context context) {
        try {
            SharedPreferences p = prefs(context);
            String stage = p.getString(STAGE, "");
            String detail = p.getString(DETAIL, "");
            long time = p.getLong(TIME, 0L);
            String audit = TokenCostAudit.uiText(context);
            String header = "引擎：Anchored / editorial-110\n当前模式：" + (CaptionChoice.translates() ? "自动翻译" : "原字幕（零翻译 API）") + "\n显示文本调试：" +
                    (DeepSeekConfig.displayTextDebugEnabled(context) ? "开" : "关");
            if (stage == null || stage.isEmpty()) {
                String base = "尚未捕获到自动翻译请求。启用并填写 API Key 后，播放视频并从“自动翻译”选择任意目标语言，再回来点“刷新诊断”。";
                return audit == null || audit.isEmpty()
                        ? header + "\n" + base
                        : header + "\n" + base + "\n\n" + audit;
            }
            long seconds = time <= 0 ? -1 : Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
            String age = seconds < 0 ? "" : "（约 " + seconds + " 秒前）";
            StringBuilder text = new StringBuilder();
            text.append(header).append("\n");
            text.append("最近阶段：").append(stage).append(age);
            if (detail != null && !detail.isEmpty()) text.append("\n").append(detail);
            if (audit != null && !audit.isEmpty()) {
                text.append("\n\n").append(audit);
            }
            String history = p.getString(HISTORY, "");
            if (history != null && !history.isEmpty()) {
                text.append("\n\n最近链路：\n").append(history);
            }
            return text.toString();
        } catch (Throwable error) {
            return "读取诊断状态失败：" + error.getClass().getSimpleName();
        }
    }

    private static String sanitize(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    static String errorDetail(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return sanitize(message, 220);
    }
}
