package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.util.AttributeSet;
import android.view.*;
/** Shared stable title binding at creation and recycling; subclasses retain their business state. */
@SuppressWarnings("deprecation")
public class CaptionUiPreference extends android.preference.Preference {
    private View uiView;
    View boundUiView(){return uiView;}
    public CaptionUiPreference(Context c){super(c);}
    public CaptionUiPreference(Context c,AttributeSet a){super(c,a);}
    public CaptionUiPreference(Context c,AttributeSet a,int d){super(c,a,d);}
    public CaptionUiPreference(Context c,AttributeSet a,int d,int r){super(c,a,d,r);}
    @Override public View getView(View convert,ViewGroup parent){
        CaptionUiWindows.bind(this,parent);
        CaptionPreferenceBindings.bind(this);
        View result=super.getView(convert,parent);uiView=result;
        CaptionPreferenceBindings.view(this,result);CaptionUiViewBindings.refresh(result,getContext());
        return result;
    }
    @Override protected void onBindView(View view){
        CaptionPreferenceBindings.bind(this);super.onBindView(view);
        CaptionPreferenceBindings.view(this,view);CaptionUiViewBindings.refresh(view,getContext());
    }
    void rebindUi(){if(uiView!=null)CaptionUiViewBindings.refresh(uiView,getContext());}
}
