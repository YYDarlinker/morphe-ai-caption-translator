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

/** Use Morphe's public dialog chrome when installed, without making the addon depend on it. */
final class CaptionSettingsDialogs {
    private CaptionSettingsDialogs() {}

    /** Host-styled confirmation, with the platform dialog only when the host class is absent. */
    static Dialog confirm(Context c,String title,String message,String confirmLabel,Runnable onConfirm){
        LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(c);label.setText(message);CaptionSettingsStyle.caption(label);body.addView(label,new LinearLayout.LayoutParams(-1,-2));
        ProfileActionStrip footer=new ProfileActionStrip(c);CaptionTextResolver.direction(footer,false);
        final Dialog[] owner={null};
        footer.add(CaptionStrings.settings(c,"cancel"),()->{if(owner[0]!=null)owner[0].dismiss();}).setTag("caption_dialog_cancel");
        footer.addPrimary(confirmLabel,()->{if(owner[0]==null||!owner[0].isShowing())return;owner[0].dismiss();onConfirm.run();}).setTag("caption_dialog_confirm");
        // CustomDialog chooses its own platform Cancel text. Own both labels in the addon locale.
        owner[0]=show(c,title,body,CaptionStrings.settings(c,"cancel"),footer);return owner[0];
    }

    static Dialog show(Context c, String title, View content, String closeLabel) {
        return show(c,title,content,closeLabel,null);
    }
    static Dialog show(Context c,String title,View content,String closeLabel,View footer){
        // A bounded scroll area also keeps actions reachable in landscape and with large fonts.
        ScrollView scroll = new ScrollView(c) {
            @Override protected void onMeasure(int width, int height) {
                int cap = Math.max(CaptionSettingsStyle.dp(c,96),
                        (int)(c.getResources().getDisplayMetrics().heightPixels * .55f));
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                    cap = Math.min(cap, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.addView(content, new ViewGroup.LayoutParams(-1,-2));
        Dialog dialog = null;
        try {
            Object result = Class.forName("app.morphe.extension.shared.ui.CustomDialog")
                    .getMethod("create", Context.class, CharSequence.class, CharSequence.class,
                            EditText.class, CharSequence.class, Runnable.class, Runnable.class,
                            CharSequence.class, Runnable.class, boolean.class, boolean.class)
                    .invoke(null, c, title, null, null, closeLabel, (Runnable)()->{}, null,
                            null, null, false, false);
            Pair<?,?> pair = (Pair<?,?>)result;
            LinearLayout main = (LinearLayout)pair.second;
            if(footer!=null){
                main.removeViewAt(main.getChildCount()-1);
                LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1,-2);
                footerParams.topMargin=CaptionSettingsStyle.dp(c,16);
                main.addView(footer,footerParams);
            }
            main.addView(scroll, main.getChildCount()-1, new LinearLayout.LayoutParams(-1,-2,1f));
            dialog = (Dialog)pair.first;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError unavailable) {
            if (scroll.getParent() instanceof ViewGroup) ((ViewGroup)scroll.getParent()).removeView(scroll);
            if (footer!=null && footer.getParent() instanceof ViewGroup) ((ViewGroup)footer.getParent()).removeView(footer);
        }
        if (dialog == null) {
            scroll.setPadding(CaptionSettingsStyle.dp(c,20),0,CaptionSettingsStyle.dp(c,20),0);
            AlertDialog.Builder builder=new AlertDialog.Builder(c).setTitle(title);
            if(footer==null)builder.setView(scroll).setPositiveButton(closeLabel,null);
            else{
                LinearLayout column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);
                column.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
                footer.setPadding(CaptionSettingsStyle.dp(c,20),CaptionSettingsStyle.dp(c,12),CaptionSettingsStyle.dp(c,20),CaptionSettingsStyle.dp(c,12));
                column.addView(footer,new LinearLayout.LayoutParams(-1,-2));builder.setView(column);
            }
            dialog=builder.create();
        }
        dialog.show();
        CaptionTextResolver.direction(dialog.getWindow().getDecorView(),false);
        return dialog;
    }
}
