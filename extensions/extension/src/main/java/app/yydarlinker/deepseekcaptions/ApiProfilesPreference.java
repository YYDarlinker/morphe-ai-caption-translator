package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import java.util.*;
/** Compact inline profile selector, rename field, and add action; shares addon typography. */
@SuppressWarnings("deprecation")
public final class ApiProfilesPreference extends android.preference.Preference {
    private boolean binding;private Spinner selector;private EditText name;private TextView hint;
    private List<String> ids=new ArrayList<>();
    public ApiProfilesPreference(Context c){super(c);init();}
    public ApiProfilesPreference(Context c,AttributeSet a){super(c,a);init();}
    public ApiProfilesPreference(Context c,AttributeSet a,int d){super(c,a,d);init();}
    public ApiProfilesPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);init();}
    private void init(){setPersistent(false);setSelectable(false);}
    @Override protected View onCreateView(ViewGroup parent){
        Context c=getContext();LinearLayout root=new LinearLayout(c);root.setOrientation(1);CaptionSettingsStyle.row(root);
        TextView title=new TextView(c);CaptionSettingsStyle.title(title);title.setText(CaptionStrings.localize(c,"API 配置方案"));root.addView(title);
        selector=new Spinner(c);root.addView(selector,new LinearLayout.LayoutParams(-1,CaptionSettingsStyle.dp(c,48)));
        name=new InlineCaptionEditor(c);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});name.setSingleLine(true);CaptionSettingsStyle.editor(name);name.setHint(CaptionStrings.settings(c,"profile_name"));root.addView(name,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(c);actions.setGravity(Gravity.END);root.addView(actions);
        Button rename=button(c,"profile_rename"),add=button(c,"profile_add");actions.addView(rename,new LinearLayout.LayoutParams(0,-2,1));actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));
        hint=new TextView(c);CaptionSettingsStyle.caption(hint);root.addView(hint);reload();
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){
                if(binding||position<0||position>=ids.size()||ids.get(position).equals(ApiProfiles.active(c)))return;
                try{if(!ApiProfiles.select(c,ids.get(position)))hint.setText(CaptionStrings.settings(c,"profile_invalid_edits"));else reload();}
                catch(Exception error){hint.setText(CaptionStrings.settings(c,"profile_invalid_edits"));}
                binding=true;selector.setSelection(ids.indexOf(ApiProfiles.active(c)),false);binding=false;
            }
        });
        rename.setOnClickListener(v->{try{ApiProfiles.rename(c,ApiProfiles.active(c),name.getText().toString());reload();}catch(Exception e){name.setError(CaptionStrings.settings(c,"profile_name_error"));}});
        add.setOnClickListener(v->{try{
            if(!ApiProfiles.flushCurrent()){hint.setText(CaptionStrings.settings(c,"profile_invalid_edits"));return;}
            String id=ApiProfiles.create(c,"API "+(ApiProfiles.list(c).size()+1),DeepSeekConfig.DEFAULT_BASE_URL);
            if(!ApiProfiles.select(c,id)){reload();hint.setText(CaptionStrings.settings(c,"profile_invalid_edits"));return;}reload();name.requestFocus();name.selectAll();
        }catch(Exception e){hint.setText(CaptionStrings.settings(c,"profile_add_failed"));}});
        return root;
    }
    private Button button(Context c,String key){Button b=new Button(c,null,android.R.attr.borderlessButtonStyle);CaptionSettingsStyle.button(b);b.setText(CaptionStrings.settings(c,key));return b;}
    private void reload(){
        binding=true;LinkedHashMap<String,String> values=ApiProfiles.list(getContext());ids=new ArrayList<>(values.keySet());
        ArrayAdapter<String> adapter=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_item,new ArrayList<>(values.values())){
            @Override public View getView(int position,View old,ViewGroup parent){View v=super.getView(position,old,parent);if(v instanceof TextView){((TextView)v).setSingleLine(true);((TextView)v).setEllipsize(android.text.TextUtils.TruncateAt.END);((TextView)v).setTextSize(16);}return v;}
        };adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);selector.setSelection(ids.indexOf(ApiProfiles.active(getContext())),false);
        name.setText(values.get(ApiProfiles.active(getContext())));hint.setText(CaptionStrings.settings(getContext(),"profiles_summary"));binding=false;
    }
}
