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
    private void refresh(){setTitle(text("languages_title"));int count=CaptionLanguageSelection.read(getContext()).size();
        setSummary(count==0?text("languages_empty"):String.format(Locale.ROOT,text("languages_count"),count));}
    static void wrap(View root) {for(int id:new int[]{android.R.id.title,android.R.id.summary,android.R.id.text1}) {
        TextView v=root.findViewById(id);if(v!=null){v.setSingleLine(false);v.setMaxLines(Integer.MAX_VALUE);v.setEllipsize(null);}
    }}
    @Override protected void onBindView(View view){refresh();super.onBindView(view);wrap(view);}
    @Override protected void onClick(){showLanguages();}
    AlertDialog showLanguages() {
        List<String> codes=CaptionLanguageSelection.CODES;Set<String> chosen=new LinkedHashSet<>(CaptionLanguageSelection.read(getContext()));
        String[] labels=new String[codes.size()];boolean[] checked=new boolean[codes.size()];
        for(int i=0;i<codes.size();i++){String code=codes.get(i);checked[i]=chosen.contains(code);
            labels[i]=String.format(Locale.ROOT,text("languages_entry"),LanguageMenuOrder.label(code),text(NativeCaptionBridge.languageStatus(code)));}
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(text("languages_title"))
            .setMultiChoiceItems(labels,checked,(d,which,on)->{if(on)chosen.add(codes.get(which));else chosen.remove(codes.get(which));})
            .setPositiveButton(text("languages_save"),(d,which)->{CaptionLanguageSelection.save(getContext(),chosen);refresh();})
            .setNegativeButton(text("cancel"),null).create();
        dialog.setOnShowListener(d->{ListView list=dialog.getListView();
            list.setAdapter(new ArrayAdapter<String>(getContext(),android.R.layout.simple_list_item_multiple_choice,android.R.id.text1,labels){
                @Override public View getView(int position,View convert,android.view.ViewGroup parent){View row=super.getView(position,convert,parent);wrap(row);return row;}
            });
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);for(int i=0;i<checked.length;i++)list.setItemChecked(i,checked[i]);
            list.setOnItemClickListener((parent,view,position,id)->{if(list.isItemChecked(position))chosen.add(codes.get(position));else chosen.remove(codes.get(position));});
        });
        dialog.show();return dialog;
    }
}
