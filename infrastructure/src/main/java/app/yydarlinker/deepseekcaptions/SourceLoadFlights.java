package app.yydarlinker.deepseekcaptions;

import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;

/** Bounded source/profile transport single-flight. A departed consumer cannot cancel another consumer. */
final class SourceLoadFlights {
  interface Loader<T> { T load(CaptionSourceControl control) throws Exception; }
  static final class Result<T> {
    final T value;
    final boolean joined;
    Result(T value, boolean joined) {this.value=value;this.joined=joined;}
  }
  private static final Map<String,Flight<?>> flights=new HashMap<>();
  private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"CaptionSharedSourceIO");t.setDaemon(true);return t;},
      new ThreadPoolExecutor.AbortPolicy());
  private static final ThreadPoolExecutor CLOSE=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"CaptionSourceDisconnect");t.setDaemon(true);return t;},
      new ThreadPoolExecutor.AbortPolicy());
  private static final class Flight<T> implements CaptionSourceControl {
    final String key;
    final FutureTask<T> task;
    int consumers;
    volatile boolean cancelled, started;
    volatile HttpURLConnection connection;
    Flight(String key, Loader<T> loader) {
      this.key=key;
      task=new FutureTask<>(()->{started=true;RawCaptionSource.checkActive(this);return loader.load(this);});
    }
    public boolean isCancelled() {return cancelled;}
    public void onConnection(HttpURLConnection connection) {
      this.connection=connection;
      // Loader callbacks occur on the transport worker, never a UI/lifecycle monitor.
      if(connection!=null && cancelled)connection.disconnect();
    }
    void abandon() {
      cancelled=true;
      if(!started && IO.remove(task))task.cancel(false);
      HttpURLConnection open=connection;
      if(open!=null) {
        try {CLOSE.execute(open::disconnect);}
        catch(RejectedExecutionException full) { /* Running transport retains its slot until bounded timeout/close. */ }
      }
    }
  }

  @SuppressWarnings("unchecked")
  static <T> Result<T> load(String key, int waitMs, CaptionSourceControl consumer,
      Loader<T> loader) throws Exception {
    RawCaptionSource.checkActive(consumer);
    Flight<T> flight; boolean joined;
    synchronized(flights) {
      flight=(Flight<T>)flights.get(key);
      joined=flight!=null;
      if(flight==null) {
        flight=new Flight<>(key,loader);flights.put(key,flight);
        try {IO.execute(flight.task);}
        catch(RejectedExecutionException full) {flights.remove(key);throw new java.io.IOException("source_flight_capacity",full);}
      }
      flight.consumers++;
    }
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,waitMs));
    try {
      for(;;) {
        RawCaptionSource.checkActive(consumer);
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new java.io.InterruptedIOException("source_consumer_deadline");
        try {
          T value=flight.task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(20)),TimeUnit.NANOSECONDS);
          RawCaptionSource.checkActive(consumer);return new Result<>(value,joined);
        } catch(TimeoutException pending) { /* Recheck this consumer without poisoning the shared request. */ }
        catch(ExecutionException failure) {
          Throwable cause=failure.getCause();
          if(cause instanceof Exception)throw (Exception)cause;
          if(cause instanceof Error)throw (Error)cause;
          throw new IllegalStateException(cause);
        }
      }
    } finally {
      boolean last;
      synchronized(flights) {
        last=--flight.consumers==0;
        if(last && flights.get(key)==flight)flights.remove(key);
      }
      // Never disconnect while holding the registry or a Session monitor.
      if(last && !flight.task.isDone())flight.abandon();
    }
  }
  static int consumers(String key) {synchronized(flights){Flight<?> flight=flights.get(key);return flight==null ? 0 : flight.consumers;}}
  static int activeFlights() {synchronized(flights){return flights.size();}}
  static int physicalWorkers() {return IO.getActiveCount();}
  static int queuedFlights() {return IO.getQueue().size();}
}
