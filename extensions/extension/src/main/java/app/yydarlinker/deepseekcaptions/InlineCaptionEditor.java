package app.yydarlinker.deepseekcaptions;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.EditText;
/** Inline Android floating text actions, independent of the preference row's long-click handling. */
public final class InlineCaptionEditor extends EditText {
    private ActionMode actions;private boolean sensitive;private float downX,downY;
    public InlineCaptionEditor(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);setLongClickable(true);setCursorVisible(true);
        setShowSoftInputOnFocus(true);setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}
    public void sensitive(boolean value){sensitive=value;}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_UP){
            performClick();requestFocus();android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if(ime!=null)post(()->ime.showSoftInput(this,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        }
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();requestFocus();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE && Math.abs(e.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()
            && Math.abs(e.getY()-downY)>Math.abs(e.getX()-downX) && actions==null){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        return super.onTouchEvent(e);
    }
    @Override public boolean performLongClick(){
        requestFocus();if(getSelectionStart()<0)setSelection(length());
        if(actions!=null){actions.finish();actions=null;}
        actions=startActionMode(new ActionMode.Callback2(){
            public boolean onCreateActionMode(ActionMode mode,Menu menu){
                menu.add(0,android.R.id.paste,0,android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0,android.R.id.selectAll,1,android.R.string.selectAll);
                if(!sensitive)menu.add(0,android.R.id.copy,2,android.R.string.copy);
                return true;
            }
            public boolean onPrepareActionMode(ActionMode mode,Menu menu){return false;}
            public boolean onActionItemClicked(ActionMode mode,MenuItem item){
                boolean handled=onTextContextMenuItem(item.getItemId());if(item.getItemId()!=android.R.id.selectAll)mode.finish();return handled;
            }
            public void onDestroyActionMode(ActionMode mode){actions=null;}
            @Override public void onGetContentRect(ActionMode mode,View view,Rect out){out.set(0,0,getWidth(),getHeight());}
        },ActionMode.TYPE_FLOATING);
        return actions!=null || super.performLongClick();
    }
    @Override protected void onDetachedFromWindow(){if(actions!=null)actions.finish();super.onDetachedFromWindow();}
}
