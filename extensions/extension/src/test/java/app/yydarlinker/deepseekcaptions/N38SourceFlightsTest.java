package app.yydarlinker.deepseekcaptions;

import static org.junit.Assert.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class N38SourceFlightsTest {
  static final class Consumer implements CaptionSourceControl {
    volatile boolean cancelled;
    public boolean isCancelled(){return cancelled;}
    public void onConnection(HttpURLConnection c){}
  }
  @Test public void twoTargetsShareOneTransportAndOneLeavingDoesNotPoisonTheOther() throws Exception {
    ExecutorService clients=Executors.newFixedThreadPool(2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    AtomicInteger calls=new AtomicInteger();Consumer first=new Consumer(),second=new Consumer();String key="share-"+System.nanoTime();
    SourceLoadFlights.Loader<String> load=control->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));assertFalse(control.isCancelled());return "verified-source";};
    try {
      Future<SourceLoadFlights.Result<String>> a=clients.submit(()->SourceLoadFlights.load(key,3000,first,load));
      assertTrue(entered.await(1,TimeUnit.SECONDS));
      CountDownLatch secondJoined=new CountDownLatch(1);
      Future<SourceLoadFlights.Result<String>> b=clients.submit(()->SourceLoadFlights.load(key,3000,second,c->{secondJoined.countDown();return load.load(c);}));
      long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
      while(SourceLoadFlights.consumers(key)!=2 && System.nanoTime()<end)Thread.yield();
      assertEquals("second consumer not registered",2,SourceLoadFlights.consumers(key));
      first.cancelled=true;
      try {a.get(1,TimeUnit.SECONDS);fail("departed consumer returned source");}
      catch(ExecutionException expected) {assertTrue(expected.getCause() instanceof InterruptedException);}
      release.countDown();SourceLoadFlights.Result<String> result=b.get(2,TimeUnit.SECONDS);
      assertEquals("verified-source",result.value);assertTrue(result.joined);assertEquals(1,calls.get());assertEquals(1,secondJoined.getCount());
    } finally {release.countDown();clients.shutdownNow();}
  }
  @Test public void independentConsumerDeadlineDoesNotCancelSharedRequest() throws Exception {
    ExecutorService clients=Executors.newFixedThreadPool(2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Consumer keeper=new Consumer();
    String key="deadline-"+System.nanoTime();AtomicInteger calls=new AtomicInteger();
    SourceLoadFlights.Loader<String> loader=c->{calls.incrementAndGet();entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));assertFalse(c.isCancelled());return "ok";};
    try {
      Future<SourceLoadFlights.Result<String>> longWait=clients.submit(()->SourceLoadFlights.load(key,3000,keeper,loader));assertTrue(entered.await(1,TimeUnit.SECONDS));
      try {SourceLoadFlights.load(key,40,new Consumer(),loader);fail("deadline not applied");}
      catch(java.io.InterruptedIOException expected) {assertEquals("source_consumer_deadline",expected.getMessage());}
      release.countDown();assertEquals("ok",longWait.get(2,TimeUnit.SECONDS).value);assertEquals(1,calls.get());
    } finally {release.countDown();clients.shutdownNow();}
  }
  @Test public void lastConsumerCancellationReleasesTheRegistryWithoutHidingPhysicalWork() throws Exception {
    ExecutorService client=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),stopped=new CountDownLatch(1);Consumer consumer=new Consumer();String key="last-"+System.nanoTime();
    try {
      Future<?> result=client.submit(()->SourceLoadFlights.load(key,3000,consumer,c->{entered.countDown();while(!c.isCancelled())Thread.sleep(5);stopped.countDown();throw new InterruptedException("cancelled");}));
      assertTrue(entered.await(1,TimeUnit.SECONDS));consumer.cancelled=true;
      try {result.get(1,TimeUnit.SECONDS);fail("cancel not observed");}catch(ExecutionException expected) {assertTrue(expected.getCause() instanceof InterruptedException);}
      assertTrue(stopped.await(1,TimeUnit.SECONDS));assertEquals(0,SourceLoadFlights.activeFlights());assertEquals(0,SourceLoadFlights.queuedFlights());assertTrue(SourceLoadFlights.physicalWorkers()<=2);
    } finally {client.shutdownNow();}
  }
}
