package app.yydarlinker.deepseekcaptions;
import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.*;
import java.util.*;
/** Ordinary Morphe Preference row and platform multi-choice dialog, available while AI is off. */
@SuppressWarnings("deprecation")
public final class CaptionLanguagesPreference extends android.preference.Preference {
    public CaptionLanguagesPreference(Context c){super(c);init();}
    public CaptionLanguagesPreference(Context c,AttributeSet a){super(c,a);init();}
    public CaptionLanguagesPreference(Context c,AttributeSet a,int d){super(c,a,d);init();}
    public CaptionLanguagesPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);init();}
    private String text(String key){return CaptionStrings.settings(getContext(),key);}
    private void init(){setPersistent(false);setIconSpaceReserved(false);setSingleLineTitle(false);refresh();}
    private void refresh(){setTitle(text("languages_title"));setSummary(text("languages_summary"));}
    static void wrap(View root) {for(int id:new int[]{android.R.id.title,android.R.id.summary,android.R.id.text1}) {
        TextView v=root.findViewById(id);if(v!=null){v.setSingleLine(false);v.setMaxLines(Integer.MAX_VALUE);v.setEllipsize(null);}
    }}
    @Override public View getView(View convert,android.view.ViewGroup parent){CaptionUiWindows.bind(this,parent);return super.getView(convert,parent);}
    @Override protected void onBindView(View view){CaptionUiWindows.bind(this,view);refresh();super.onBindView(view);wrap(view);CaptionUiLocale.direction(view,getContext());}
    @Override protected void onClick(){showLanguages();}
    AlertDialog showLanguages() {
        android.app.Dialog existing=CaptionUiWindows.find(getContext(),"languages");
        if(existing instanceof AlertDialog)return (AlertDialog)existing;
        CaptionUiWindows.Session window=CaptionUiWindows.acquire(getContext(),"languages");
        if(window==null)return null;
        List<String> codes=CaptionLanguageSelection.CODES;Set<String> chosen=new LinkedHashSet<>(CaptionLanguageSelection.read(getContext()));
        String[] labels=new String[codes.size()];boolean[] checked=new boolean[codes.size()];
        CaptionUiLocale.Snapshot ui=CaptionUiLocale.snapshot(getContext());Context display=window.context;
        for(int i=0;i<codes.size();i++){String code=codes.get(i);checked[i]=chosen.contains(code);
            labels[i]=LanguageMenuOrder.label(code,ui.locale);}
        AlertDialog dialog=new AlertDialog.Builder(display).setTitle(text("languages_title"))
            .setMultiChoiceItems(labels,checked,(d,which,on)->{if(on)chosen.add(codes.get(which));else chosen.remove(codes.get(which));})
            .setPositiveButton(text("languages_save"),(d,which)->{if(window.current()){CaptionLanguageSelection.save(getContext(),chosen);refresh();}})
            .setNegativeButton(text("cancel"),null).create();
        dialog.setOnShowListener(d->{ListView list=dialog.getListView();
            if(!dialog.isShowing())return;
            CaptionUiLocale.direction(list,getContext());
            list.setAdapter(new ArrayAdapter<String>(display,android.R.layout.simple_list_item_multiple_choice,android.R.id.text1,labels){
                @Override public View getView(int position,View convert,android.view.ViewGroup parent){View row=super.getView(position,convert,parent);wrap(row);return row;}
            });
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);for(int i=0;i<checked.length;i++)list.setItemChecked(i,checked[i]);
            list.setOnItemClickListener((parent,view,position,id)->{if(list.isItemChecked(position))chosen.add(codes.get(position));else chosen.remove(codes.get(position));});
        });
        return CaptionUiWindows.show(window,dialog)?dialog:null;
    }
}
