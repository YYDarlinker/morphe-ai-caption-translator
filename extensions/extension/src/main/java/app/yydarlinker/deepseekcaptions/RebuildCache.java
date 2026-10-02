package app.yydarlinker.deepseekcaptions;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Independently versioned, atomic per-block storage. Every hit is revalidated before publication.
 */
final class RebuildCache {
  static String hash(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
      return b.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Historical English/Chinese cache identity for legacy fixtures only. */
  static String identity(RebuildSource s, DeepSeekConfig.Snapshot c, String target) {
    return identity(s,c,target,CaptionLanguageContext.LEGACY);
  }
  static String identity(RebuildSource s,DeepSeekConfig.Snapshot c,String target,CaptionLanguageContext context) {
    StringBuilder b =
        new StringBuilder(RebuildProtocol.VERSION)
            .append('|')
            .append(context.fingerprint(c))
            .append('|')
            .append(target)
            .append('|')
            .append(hash(RebuildApi.prompt(c, target,context)));
    if(!context.canApplyEnglishToChinese)b.append('|').append(context.scope());
    for (RebuildSource.Word w : s.words)
      b.append('\n')
          .append(w.start)
          .append(':')
          .append(w.end)
          .append(':')
          .append(w.precision)
          .append(':')
          .append(w.text);
    return hash(b.toString());
  }

  static synchronized void clear(Context c) {
    File[] fs = directory(c).listFiles();
    if (fs != null) for (File f : fs) if (f.isFile()) f.delete();
  }

  static File directory(Context c) {
    File f = new File(c.getCacheDir(), "caption-events-r2.12");
    if (!f.exists()) f.mkdirs();
    return f;
  }

  /** Explicit legacy fixture entry; production reads must pass their immutable context. */
  static RebuildProtocol.Plan read(Context c, String key, RebuildSource s, RebuildPlanner.Block b) {
    return read(c,key,s,b,CaptionLanguageContext.LEGACY);
  }
  static RebuildProtocol.Plan read(Context c,String key,RebuildSource s,RebuildPlanner.Block b,
                                   CaptionLanguageContext context) {
    File f = new File(directory(c), key + "-" + b.id() + ".json");
    if (!f.isFile()) return null;
    try {
      if (f.length() > 256000) throw new IOException("oversized");
      String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      RebuildProtocol.Plan plan=RebuildProtocol.parseBound(raw,s,b,context);
      if(!context.canApplyEnglishToChinese)
        plan=RebuildReview.withLayoutReview(plan,CaptionOverlay.budget(),context);
      return RebuildReview.score(plan.issues)==0 ? plan : null;
    } catch (Exception bad) {
      f.delete();
      return null;
    }
  }

  static synchronized boolean write(
      Context c, String key, RebuildPlanner.Block b, RebuildProtocol.Plan p) {
    if(RebuildReview.score(p.issues)>0)return false;
    File dir = directory(c), dest = new File(dir, key + "-" + b.id() + ".json");
    File tmp = null;
    try {
      tmp = File.createTempFile("events-", ".tmp", dir);
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(p.json.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      java.nio.file.Files.move(
          tmp.toPath(),
          dest.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      File[] all = dir.listFiles((x, n) -> n.endsWith(".json"));
      if (all == null) return true;
      Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
      long bytes = 0;
      for (int i = 0; i < all.length; i++) {
        bytes += all[i].length();
        if (i >= 256 || bytes > 64L * 1024 * 1024) all[i].delete();
      }
      return true;
    } catch (Exception ignored) {
      return false;
    } finally {
      if (tmp != null) tmp.delete();
    }
  }
  static boolean write(Context c,String key,RebuildSource source,RebuildPlanner.Block block,
      RebuildProtocol.Plan plan,CaptionLanguageContext context) {
    if(context.canApplyEnglishToChinese)return write(c,key,block,plan);
    try {
      // Same source ownership and language policy on write and on a future cold read.
      RebuildProtocol.Plan verified=RebuildProtocol.parseBound(plan.json,source,block,context);
      if(RebuildReview.score(verified.issues)>0)return false;
      return write(c,key,block,plan);
    } catch(Exception invalid) { return false; }
  }
}
