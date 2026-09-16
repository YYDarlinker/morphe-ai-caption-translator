import android.os.Looper;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** YouTube 21.07.247 ART regression. Executes the real shared dispatcher up to its renderer
 * boundary using constructor-free host objects. No Activity/account/network or playback claim. */
public final class ArtCaptionHandoffProbe {
    static final String PREFIX="app.yydarlinker.deepseekcaptions.";
    static Object unsafe;
    static Method allocate;
    static Object alloc(Class<?> c)throws Exception{return allocate.invoke(unsafe,c);}
    static Object get(Class<?> c,Object o,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Class<?> c,Object o,String name,Object value)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object invoke(Class<?> c,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        try { run(); } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
    static void run()throws Exception {
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> u=Class.forName("sun.misc.Unsafe");unsafe=get(u,null,"theUnsafe");allocate=u.getMethod("allocateInstance",Class.class);
        Class<?> bridge=Class.forName(PREFIX+"NativeCaptionBridge"),page=Class.forName(PREFIX+"PageCaptionController"),choice=Class.forName(PREFIX+"CaptionChoice");
        Class<?> managerClass=Class.forName("anws"),trackClass=Class.forName("anyg"),eventClass=Class.forName("amof"),originClass=Class.forName("amol");
        Class<?> auto=Class.forName("app.morphe.extension.youtube.patches.AutoCaptionsPatch");
        ((AtomicBoolean)get(auto,null,"captionsButtonStatus")).set(true); // bypass settings, preserve native track filtering
        Object origin=null;for(Object item:originClass.getEnumConstants())if(((Enum<?>)item).name().equals("DEFAULT"))origin=item;
        require(origin!=null,"Native DEFAULT origin missing");
        Constructor<?> eventCtor=eventClass.getConstructor(trackClass,originClass,int.class,String.class);
        Method dispatch=managerClass.getMethod("l",eventClass);
        for(int i=1;i<=20;i++){
            String video=String.format(Locale.ROOT,"%011d",i);
            set(page,null,"currentId",video);
            invoke(bridge,"onVideoId",new Class<?>[]{String.class},video);
            Object track=alloc(trackClass),manager=alloc(managerClass);
            set(trackClass,track,"a","en");set(trackClass,track,"k",".en");set(trackClass,track,"d",video);
            set(trackClass,track,"l","https://www.youtube.com/api/timedtext?v="+video+"&lang=en");
            Object event=eventCtor.newInstance(track,origin,2,video);
            try{dispatch.invoke(manager,event);throw new AssertionError("Fixture must stop at renderer boundary");}
            catch(InvocationTargetException expected){
                Throwable cause=expected.getCause();require(cause instanceof NullPointerException,"Unexpected host failure: "+cause);
                require(Arrays.stream(cause.getStackTrace()).anyMatch(f->f.getClassName().equals("anws")&&f.getMethodName().equals("f")),"Did not reach renderer boundary: "+cause);
            }
            require(Boolean.TRUE.equals(invoke(choice,"isOn",new Class<?>[]{})),"Automatic dispatcher did not capture video "+i);
            Map<?,?> selections=(Map<?,?>)get(bridge,null,"selections");Object selected=selections.get(video);
            require(selected!=null,"No snapshot for video "+i);require(selections.size()<=6,"Snapshot bound exceeded");
            require(((java.lang.ref.WeakReference<?>)get(selected.getClass(),selected,"manager")).get()==manager,"Wrong manager");
            require(((String)get(selected.getClass(),selected,"selectedUrl")).contains(video),"Wrong video URL");
        }
        System.out.println("ART_AUTOMATIC_HANDOFF_PASS videos=20 actual_dispatcher=true renderer_boundary_reached=true network=false");
    }
}
