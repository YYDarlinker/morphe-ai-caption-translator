package app.yydarlinker.deepseekcaptions;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

/** One ordinary settings row; management controls appear only on explicit request. */
@SuppressWarnings("deprecation")
public final class ApiProfilesPreference extends android.preference.Preference {
    private Dialog dialog;
    public ApiProfilesPreference(Context c){super(c);init();}
    public ApiProfilesPreference(Context c,AttributeSet a){super(c,a);init();}
    public ApiProfilesPreference(Context c,AttributeSet a,int d){super(c,a,d);init();}
    public ApiProfilesPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);init();}
    private void init(){setPersistent(false);setSelectable(true);}

    @Override protected void onBindView(View view) {
        setTitle(text("profiles_title"));
        setSummary(ApiProfiles.list(getContext()).get(ApiProfiles.active(getContext())));
        super.onBindView(view);
        TextView summary=view.findViewById(android.R.id.summary);
        if(summary!=null){summary.setMaxLines(1);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);}
    }
    @Override protected void onClick(){showProfiles();}
    private String text(String key){return CaptionStrings.settings(getContext(),key);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(1);return v;}
    private void show(String title, LinearLayout content, String close){
        if(dialog!=null)dialog.dismiss();
        dialog=CaptionSettingsDialogs.show(getContext(),title,content,text(close));
    }
    private void close(){if(dialog!=null)dialog.dismiss();dialog=null;notifyChanged();}
    private void message(LinearLayout parent,String value){
        TextView v=new TextView(getContext());CaptionSettingsStyle.caption(v);v.setText(value);
        v.setPadding(0,CaptionSettingsStyle.dp(getContext(),8),0,CaptionSettingsStyle.dp(getContext(),12));
        parent.addView(v,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView action(LinearLayout parent,String label,Runnable click){
        TextView v=new TextView(getContext());CaptionSettingsStyle.title(v);v.setText(label);
        int pad=CaptionSettingsStyle.dp(getContext(),12);
        v.setPadding(pad,pad,pad,pad);v.setMinHeight(CaptionSettingsStyle.dp(getContext(),48));
        v.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);v.setFocusable(true);v.setClickable(true);
        // No selected-row fill: monochrome checkmark + bounded neutral ripple, no blue corner mismatch.
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(CaptionSettingsStyle.tint(CaptionSettingsStyle.primary(getContext()),24)),
                null,new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE)));
        v.setOnClickListener(w->click.run());parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private void error(String key){Toast.makeText(getContext(),text(key),Toast.LENGTH_LONG).show();}
    private boolean flush(){if(ApiProfiles.flushCurrent())return true;error("profile_invalid_edits");return false;}

    void showProfiles(){
        LinearLayout body=column();String active=ApiProfiles.active(getContext());
        for(Map.Entry<String,String> entry:ApiProfiles.list(getContext()).entrySet()){
            final String id=entry.getKey();boolean selected=id.equals(active);
            TextView row=action(body,(selected?"✓  ":"    ")+entry.getValue(),()->{
                if(ApiProfiles.select(getContext(),id))close();else error("profile_invalid_edits");
            });
            row.setSelected(selected);
            row.setContentDescription(entry.getValue()+(selected?", "+text("profile_current"):""));
        }
        View divider=new View(getContext());divider.setBackgroundColor(CaptionSettingsStyle.tint(CaptionSettingsStyle.primary(getContext()),24));
        body.addView(divider,new LinearLayout.LayoutParams(-1,CaptionSettingsStyle.dp(getContext(),1)));
        action(body,text("profile_add"),()->{if(flush())editName(null);});
        action(body,text("profile_manage"),()->showManage(ApiProfiles.active(getContext())));
        show(text("profiles_title"),body,"profile_close");
    }
    private void showManage(String id){
        String name=ApiProfiles.list(getContext()).get(id);if(name==null)return;
        LinearLayout body=column();
        action(body,text("profile_rename"),()->editName(id));
        action(body,text("profile_clear_key"),()->{if(flush())confirm(id,false);});
        if(ApiProfiles.list(getContext()).size()>1)action(body,text("profile_delete"),()->confirm(id,true));
        else message(body,text("profile_keep_one"));
        show(name,body,"profile_close");
    }
    private void editName(String id){
        LinearLayout body=column();EditText name=new InlineCaptionEditor(getContext());
        CaptionSettingsStyle.editor(name);name.setSingleLine(true);name.setHint(text("profile_name"));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        name.setText(id==null?"":ApiProfiles.list(getContext()).get(id));body.addView(name,new LinearLayout.LayoutParams(-1,-2));
        if(id==null)message(body,text("profile_new_summary"));
        action(body,text("profile_save"),()->{
            String value=name.getText().toString().trim();
            if(value.isEmpty()){name.setError(text("profile_name_error"));name.requestFocus();return;}
            try{
                if(id==null){
                    if(!flush())return;
                    String created=ApiProfiles.create(getContext(),value,DeepSeekConfig.DEFAULT_BASE_URL);
                    if(!ApiProfiles.select(getContext(),created)){error("profile_invalid_edits");return;}
                }else ApiProfiles.rename(getContext(),id,value);
                close();
            }catch(IllegalArgumentException invalid){name.setError(text("profile_name_error"));}
            catch(IllegalStateException failed){error("profile_add_failed");}
        });
        show(text(id==null?"profile_add":"profile_rename"),body,"cancel");
    }
    private void confirm(String id,boolean delete){
        String name=ApiProfiles.list(getContext()).get(id);if(name==null)return;
        LinearLayout body=column();message(body,text(delete?"profile_delete_summary":"profile_clear_key_summary"));
        action(body,text(delete?"profile_delete":"profile_clear_key"),()->{
            // Capture identity, not active(): an old confirmation must never target a new selection.
            if(!ApiProfiles.list(getContext()).containsKey(id)){close();return;}
            if(delete && ApiProfiles.list(getContext()).size()<=1){error("profile_keep_one");return;}
            if(delete)ApiProfiles.delete(getContext(),id);else ApiProfiles.clearKey(getContext(),id);
            close();
        });
        show(name,body,"cancel");
    }
    @Override protected void onPrepareForRemoval(){close();super.onPrepareForRemoval();}
}
