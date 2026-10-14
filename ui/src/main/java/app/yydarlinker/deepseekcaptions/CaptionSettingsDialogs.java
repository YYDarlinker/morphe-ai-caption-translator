package app.yydarlinker.deepseekcaptions;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Uses Morphe's public dialog chrome when installed, without making the add-on depend on it. */
final class CaptionSettingsDialogs {
    private CaptionSettingsDialogs() {}

    /** A confirmation whose two buttons are both labelled in the add-on's own language. */
    static Dialog confirm(
            Context c, String title, String message, String confirmLabel, Runnable onConfirm) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(c);
        label.setText(message);
        CaptionSettingsStyle.caption(label);
        body.addView(label, new LinearLayout.LayoutParams(-1, -2));

        ProfileActionStrip footer = new ProfileActionStrip(c);
        CaptionTextResolver.direction(footer, false);
        Dialog[] owner = {null};
        footer.add(CaptionStrings.get(c, "cancel"), () -> owner[0].dismiss())
                .setTag("caption_dialog_cancel");
        footer.addPrimary(
                        confirmLabel,
                        () -> {
                            if (!owner[0].isShowing()) return;
                            owner[0].dismiss();
                            onConfirm.run();
                        })
                .setTag("caption_dialog_confirm");
        owner[0] = show(c, title, body, CaptionStrings.get(c, "cancel"), footer);
        return owner[0];
    }

    static Dialog show(Context c, String title, View content, String closeLabel) {
        return show(c, title, content, closeLabel, null);
    }

    /** Shows [content] in a bounded scroll area, so actions stay reachable in landscape. */
    static Dialog show(Context c, String title, View content, String closeLabel, View footer) {
        ScrollView scroll = new BoundedScrollView(c);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        Dialog dialog = morpheDialog(c, title, closeLabel, scroll, footer);
        if (dialog == null) {
            dialog = platformDialog(c, title, closeLabel, scroll, footer);
        }
        dialog.show();
        CaptionTextResolver.direction(dialog.getWindow().getDecorView(), false);
        return dialog;
    }

    /** At most 55% of the screen high, and never more than the parent allows. */
    private static final class BoundedScrollView extends ScrollView {
        BoundedScrollView(Context c) {
            super(c);
            setFillViewport(false);
        }

        @Override
        protected void onMeasure(int width, int height) {
            int cap =
                    Math.max(
                            CaptionSettingsStyle.dp(getContext(), 96),
                            (int)
                                    (getContext().getResources().getDisplayMetrics().heightPixels
                                            * .55f));
            if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                cap = Math.min(cap, MeasureSpec.getSize(height));
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    /** The dialog of Morphe's CustomDialog with [scroll] (and [footer]) put into it, or null. */
    private static Dialog morpheDialog(
            Context c, String title, String closeLabel, ScrollView scroll, View footer) {
        try {
            Pair<?, ?> pair =
                    (Pair<?, ?>)
                            Class.forName("app.morphe.extension.shared.ui.CustomDialog")
                                    .getMethod(
                                            "create",
                                            Context.class,
                                            CharSequence.class,
                                            CharSequence.class,
                                            EditText.class,
                                            CharSequence.class,
                                            Runnable.class,
                                            Runnable.class,
                                            CharSequence.class,
                                            Runnable.class,
                                            boolean.class,
                                            boolean.class)
                                    .invoke(
                                            null,
                                            c,
                                            title,
                                            null,
                                            null,
                                            closeLabel,
                                            (Runnable) () -> {},
                                            null,
                                            null,
                                            null,
                                            false,
                                            false);
            LinearLayout main = (LinearLayout) pair.second;
            if (footer != null) {
                main.removeViewAt(main.getChildCount() - 1);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = CaptionSettingsStyle.dp(c, 16);
                main.addView(footer, params);
            }
            main.addView(
                    scroll, main.getChildCount() - 1, new LinearLayout.LayoutParams(-1, -2, 1f));
            return (Dialog) pair.first;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError unavailable) {
            detach(scroll);
            detach(footer);
            return null;
        }
    }

    private static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup)
            ((ViewGroup) view.getParent()).removeView(view);
    }

    private static Dialog platformDialog(
            Context c, String title, String closeLabel, ScrollView scroll, View footer) {
        int side = CaptionSettingsStyle.dp(c, 20);
        scroll.setPadding(side, 0, side, 0);
        AlertDialog.Builder builder = new AlertDialog.Builder(c).setTitle(title);
        if (footer == null)
            return builder.setView(scroll).setPositiveButton(closeLabel, null).create();
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        footer.setPadding(
                side, CaptionSettingsStyle.dp(c, 12), side, CaptionSettingsStyle.dp(c, 12));
        column.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return builder.setView(column).create();
    }
}
