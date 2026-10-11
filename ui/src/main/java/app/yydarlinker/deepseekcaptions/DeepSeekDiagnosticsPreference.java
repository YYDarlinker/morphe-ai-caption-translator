package app.yydarlinker.deepseekcaptions;
import android.content.*;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
/** Quiet collapsed rows create no report views and do not inspect diagnostic snapshots. */
@SuppressWarnings("deprecation")
public final class DeepSeekDiagnosticsPreference extends CaptionSettingPreference {
    public DeepSeekDiagnosticsPreference(Context c){super(c);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a){super(c,a);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a,int d){super(c,a,d);init();}
    public DeepSeekDiagnosticsPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);init();}
    private java.lang.ref.WeakReference<Panel> currentPanel=new java.lang.ref.WeakReference<>(null);
    private final class Panel {
        final DeepSeekDiagnosticsPreference owner=DeepSeekDiagnosticsPreference.this;
        final java.lang.ref.WeakReference<LinearLayout> root;
        java.lang.ref.WeakReference<TextView> body=new java.lang.ref.WeakReference<>(null);
        java.lang.ref.WeakReference<LinearLayout> expanded=new java.lang.ref.WeakReference<>(null);
        long request;
        String lastLocale="";
        CaptionDiagnosticSnapshot.Snapshot complete;
        CaptionDiagnosticSnapshot.Callback callback;
        Panel(LinearLayout root){this.root=new java.lang.ref.WeakReference<>(root);}
        boolean open(){View view=expanded.get();return view!=null&&view.getVisibility()==View.VISIBLE;}
        void ensureExpanded(){
            if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("diagnostics_view_thread");
            if(expanded.get()!=null)return;
            LinearLayout box=root.get();if(box==null)return;Context c=getContext();
            LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);content.setVisibility(View.GONE);
            TextView text=new TextView(c);text.setTag("ai_diagnostics_body");CaptionSettingsStyle.caption(text);text.setTextIsSelectable(true);
            text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setPadding(CaptionSettingsStyle.dp(c,12),CaptionSettingsStyle.dp(c,10),CaptionSettingsStyle.dp(c,12),CaptionSettingsStyle.dp(c,10));
            ScrollView scroll=new ScrollView(c){@Override public boolean onInterceptTouchEvent(MotionEvent e){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return super.onInterceptTouchEvent(e);}};
            scroll.setTag("ai_diagnostics_scroll");scroll.setBackground(CaptionSettingsStyle.surface(c,false));scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);scroll.addView(text,new ScrollView.LayoutParams(-1,-2));
            body=new java.lang.ref.WeakReference<>(text);expanded=new java.lang.ref.WeakReference<>(content);
            ProfileActionStrip actions=new ProfileActionStrip(c),archiveActions=new ProfileActionStrip(c);
            Button refresh=CaptionSettingsStyle.action(c,CaptionStrings.settings(c,"refresh"),false,false,()->{});refresh.setTag("ai_diagnostics_refresh");refresh.setOnClickListener(v->{refresh();scroll.scrollTo(0,0);});actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));
            Button copy=CaptionSettingsStyle.action(c,CaptionStrings.settings(c,"copy"),false,false,()->{});copy.setTag("ai_diagnostics_copy");actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            copy.setOnClickListener(v->export(c,copy,false));
            Button save=CaptionSettingsStyle.action(c,CaptionStrings.settings(c,"save_diagnostics"),false,false,()->{});save.setTag("ai_diagnostics_save");archiveActions.addView(save,new LinearLayout.LayoutParams(0,-2,1));save.setOnClickListener(v->export(c,save,true));
            Button clear=CaptionSettingsStyle.action(c,CaptionStrings.settings(c,"clear_diagnostics"),false,true,()->{});clear.setTag("ai_diagnostics_clear");
            clear.setOnClickListener(v->CaptionSettingsDialogs.confirm(c,CaptionStrings.settings(c,"clear_diagnostics"),CaptionStrings.settings(c,"clear_diagnostics_confirm"),CaptionStrings.settings(c,"clear_diagnostics"),()->{cancel();CaptionDiagnostics.clear(c);complete=null;refresh();}));
            content.addView(scroll,new LinearLayout.LayoutParams(-1,CaptionSettingsStyle.dp(c,240)));
            int gap=CaptionSettingsStyle.dp(c,8);actions.setPadding(0,gap,0,0);archiveActions.setPadding(0,gap,0,0);
            content.addView(actions,new LinearLayout.LayoutParams(-1,-2));content.addView(archiveActions,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams clearParams=new LinearLayout.LayoutParams(-1,-2);clearParams.topMargin=gap;content.addView(clear,clearParams);box.addView(content);
            uiText(refresh,"refresh");uiText(copy,"copy");uiText(save,"save_diagnostics");uiText(clear,"clear_diagnostics");
            uiText(text,this::preview);
        }
        String preview(){return complete!=null&&complete.key.current(getContext())?complete.preview:CaptionStrings.settings(getContext(),"diagnostics_loading");}
        void refresh(){
            View view=root.get();TextView text=body.get();if(view==null||text==null||!open())return;
            complete=CaptionDiagnosticSnapshot.peek(getContext());String value=preview();if(!sameText(text.getText(),value))text.setText(value);
            final long ticket=++request;final Object token=view.getWindowToken();final View window=view.getRootView();
            lastLocale=CaptionTextResolver.locale(getContext()).toLanguageTag();
            callback=snapshot->{
                View row=root.get();TextView target=body.get();
                if(currentPanel.get()!=this||ticket!=request||row==null||target==null||!open()||!row.isAttachedToWindow()||row.getRootView()!=window||row.getWindowToken()!=token||!snapshot.key.current(getContext()))return;
                complete=snapshot;if(!sameText(target.getText(),snapshot.preview))target.setText(snapshot.preview);
            };
            CaptionDiagnosticSnapshot.request(getContext(),callback);
        }
        void export(Context c,Button button,boolean save){
            View view=root.get();if(view==null)return;
            final long ticket=request;final Object token=view.getWindowToken();final View window=view.getRootView();final CaptionDiagnosticSnapshot.Key key=new CaptionDiagnosticSnapshot.Key(c);
            button.setEnabled(false);new Thread(()->{
                String report=CaptionDiagnostics.fullText(c),result=null;boolean failed=false;
                if(save)try{result=writeReport(c,report);}catch(Exception error){result=error.getClass().getSimpleName();failed=true;}
                String value=result;boolean failure=failed;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                    button.setEnabled(true);View row=root.get();
                    if(currentPanel.get()!=this||request!=ticket||!open()||row==null||!row.isAttachedToWindow()||row.getRootView()!=window||row.getWindowToken()!=token||!key.current(c))return;
                    if(failure)Toast.makeText(c,String.format(java.util.Locale.ROOT,CaptionStrings.settings(c,"save_failed"),value),Toast.LENGTH_LONG).show();
                    else if(save&&value!=null)Toast.makeText(c,String.format(java.util.Locale.ROOT,CaptionStrings.settings(c,"save_ok"),value),Toast.LENGTH_LONG).show();
                    else if(report.length()>60000)copyPages(c,report);
                    else {ClipboardManager manager=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);if(manager!=null){manager.setPrimaryClip(ClipData.newPlainText(CaptionStrings.settings(c,"diagnostics"),report));Toast.makeText(c,CaptionStrings.settings(c,"message_896c4b51d7e9"),Toast.LENGTH_SHORT).show();}}
                });
            },"caption-export").start();
        }
        void cancel(){request++;callback=null;}
    }
    @Override public View getView(View convert,ViewGroup parent){
        if(convert!=null&&(!(convert.getTag() instanceof DeepSeekDiagnosticsPreference.Panel)||((Panel)convert.getTag()).owner!=this))convert=null;
        return super.getView(convert,parent);
    }
    @Override protected void onBindView(View row){
        Panel panel=row.getTag() instanceof DeepSeekDiagnosticsPreference.Panel?(Panel)row.getTag():null;
        if(panel!=null&&panel.owner==this){Panel old=currentPanel.get();if(old!=null&&old!=panel)old.cancel();currentPanel=new java.lang.ref.WeakReference<>(panel);}
        super.onBindView(row);
    }
    @Override protected void refreshDynamicText(){Panel panel=currentPanel.get();if(panel!=null&&panel.open()&&!panel.lastLocale.equals(CaptionTextResolver.locale(getContext()).toLanguageTag()))panel.refresh();}
    private void init(){setPersistent(false);setSelectable(false);}
    @Override protected View onCreateView(ViewGroup parent){
        Context c=getContext();LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);CaptionSettingsStyle.row(box);
        LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(c);uiText(title,"diagnostics");CaptionSettingsStyle.title(title);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button toggle=new Button(c,null,android.R.attr.borderlessButtonStyle);CaptionSettingsStyle.button(toggle);toggle.setTag("ai_diagnostics_toggle");header.addView(toggle);box.addView(header);
        TextView hint=new TextView(c);uiText(hint,"diagnostics_hint");CaptionSettingsStyle.caption(hint);box.addView(hint);
        Panel old=currentPanel.get();if(old!=null)old.cancel();Panel panel=new Panel(box);currentPanel=new java.lang.ref.WeakReference<>(panel);box.setTag(panel);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){if(panel.open())panel.refresh();}public void onViewDetachedFromWindow(View v){panel.cancel();}});
        uiText(toggle,()->{String label=CaptionStrings.settings(c,panel.open()?"collapse":"expand");toggle.setContentDescription(label);return label;});
        toggle.setOnClickListener(v->{boolean open=!panel.open();if(open)panel.ensureExpanded();View content=panel.expanded.get();if(content!=null)content.setVisibility(open?View.VISIBLE:View.GONE);if(open)panel.refresh();else panel.cancel();String label=CaptionStrings.settings(c,open?"collapse":"expand");toggle.setText(label);toggle.setContentDescription(label);});return box;
    }
    static String saveReport(Context c,String report) throws java.io.IOException {
        String name=writeReport(c,report);
        return name==null?null:String.format(java.util.Locale.ROOT,CaptionStrings.settings(c,"save_ok"),name);
    }
    private static String writeReport(Context c,String report) throws java.io.IOException {
        if(android.os.Build.VERSION.SDK_INT<29)return null;
        android.content.ContentValues values=new android.content.ContentValues();
        String name="caption-diagnostics-"+app.yydarlinker.extension.BuildConfig.CAPTION_PATCH_VERSION+"-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new java.util.Date())+".txt";
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS);
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
        android.content.ContentResolver resolver=c.getContentResolver();
        android.net.Uri uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new java.io.IOException("No download destination");
        try {
            try(java.io.OutputStream out=resolver.openOutputStream(uri)){if(out==null)throw new java.io.IOException("No output stream");out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new java.io.IOException("Download was not published");
        }catch(Exception e){resolver.delete(uri,null,null);throw new java.io.IOException(e);}
        // Only the file name is substituted; the whole sentence is one authored template per locale.
        return name;
    }
    /**
     * Android 9 cannot publish a file through MediaStore, so the export is handed over in clipboard
     * chunks. Each label and toast is one complete authored template, never a concatenated fragment.
     */
    private static void copyPages(Context c,String report){
        int length=60000,count=(report.length()+length-1)/length;String[] labels=new String[count];
        for(int i=0;i<count;i++)labels[i]=String.format(java.util.Locale.ROOT,
                CaptionStrings.settings(c,"copy_part_label"),i+1,count);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(c)
                .setTitle(CaptionStrings.settings(c,"copy_parts_title"))
                .setNegativeButton(CaptionStrings.settings(c,"cancel"),null)
                .setItems(labels,(d,index)->{
            ClipboardManager manager=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);
            String chunk=report.substring(index*length,Math.min(report.length(),(index+1)*length));
            if(manager!=null)manager.setPrimaryClip(ClipData.newPlainText(
                    String.format(java.util.Locale.ROOT,CaptionStrings.settings(c,"copy_part_label"),index+1,count),chunk));
            Toast.makeText(c,String.format(java.util.Locale.ROOT,
                    CaptionStrings.settings(c,"copy_part_done"),index+1,count),Toast.LENGTH_LONG).show();
        }).create();dialog.show();CaptionTextResolver.direction(dialog.getWindow().getDecorView(),false);
        Button cancel=dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);if(cancel!=null)CaptionTextResolver.direction(cancel,false);
    }
}
