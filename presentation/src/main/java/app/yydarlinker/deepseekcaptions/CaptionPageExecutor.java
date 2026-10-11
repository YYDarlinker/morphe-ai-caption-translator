package app.yydarlinker.deepseekcaptions;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One bounded background lane and cache for application-provided immutable page plans. */
final class CaptionPageExecutor {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"CaptionPresentation");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String,List<CaptionPageLayout.Page>> cache=new LinkedHashMap<>(64,.75f,true);
    private final Set<String> running=new HashSet<>();
    synchronized List<CaptionPageLayout.Page> get(String key){return cache.get(key);}
    synchronized void remove(String key){cache.remove(key);}
    private final class Job implements Runnable {
        final String key;
        final Supplier<List<CaptionDisplayPage>> task;
        final Consumer<List<CaptionPageLayout.Page>> ready;
        Job(String key,Supplier<List<CaptionDisplayPage>> task,Consumer<List<CaptionPageLayout.Page>> ready){this.key=key;this.task=task;this.ready=ready;}
        public void run(){
            List<CaptionPageLayout.Page> result=Collections.emptyList();
            try{result=CaptionPageLayout.adapt(task.get());}catch(RuntimeException failure){/* Failed geometry stays bounded and cannot publish partial pages. */}
            synchronized(CaptionPageExecutor.this){running.remove(key);cache.put(key,result);while(cache.size()>64)cache.remove(cache.keySet().iterator().next());}
            ready.accept(result);
        }
    }
    synchronized void request(String key,Supplier<List<CaptionDisplayPage>> task,Consumer<List<CaptionPageLayout.Page>> ready){
        if(running.contains(key))return;
        Job job=new Job(key,task,ready);running.add(key);
        try{worker.execute(job);}catch(RejectedExecutionException full){
            // Pending layouts have no publication authority. Replace the oldest pending request
            // so rapid surface/owner changes cannot starve the newest CURRENT geometry.
            Runnable obsolete=worker.getQueue().poll();
            if(obsolete instanceof CaptionPageExecutor.Job)running.remove(((Job)obsolete).key);
            try{worker.execute(job);}catch(RejectedExecutionException stopped){running.remove(key);}
        }
    }
}
