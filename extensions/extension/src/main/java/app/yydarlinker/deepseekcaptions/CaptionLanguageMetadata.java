package app.yydarlinker.deepseekcaptions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Narrow protobuf wire adapter. Host message schema is checked by the patch before installing. */
public final class CaptionLanguageMetadata {
    private CaptionLanguageMetadata() {}
    public static byte[] addSimplified(byte[] original) {
        if(original==null || original.length>1024*1024) return original;
        try {
            List<Field> root=fields(original);
            byte[] prototype=null;
            for(Field f:root) if(f.number==3 && f.wire==2) {
                for(Field v:fields(f.value)) if(v.number==1 && v.wire==2) {
                    String code=new String(v.value,StandardCharsets.UTF_8);
                    if(code.equals("zh-Hans") || code.equals("zh-CN")) return original;
                    if(prototype==null) prototype=f.value;
                }
            }
            if(prototype==null) return original; // No invented source track or unavailable menu.
            ByteArrayOutputStream entry=new ByteArrayOutputStream();
            write(entry,1,"zh-Hans".getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream label=new ByteArrayOutputStream();
            write(label,4,"中文（简体）".getBytes(StandardCharsets.UTF_8));
            write(entry,2,label.toByteArray());
            for(Field f:fields(prototype)) if(f.number!=1 && f.number!=2) entry.write(f.raw);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            // Prepend only a new translation language; all original fields/unknown data stay intact.
            write(out,3,entry.toByteArray());out.write(original);return out.toByteArray();
        } catch(Exception malformed) { return original; }
    }
    static void write(ByteArrayOutputStream out,int number,byte[] value) {
        varint(out,(number<<3)|2);varint(out,value.length);out.write(value,0,value.length);
    }
    private static void varint(ByteArrayOutputStream out,long n) {
        while((n&~127L)!=0) { out.write((int)(n&127)|128);n>>>=7; }out.write((int)n);
    }
    private static long read(byte[] b,int[] p) {
        long n=0;
        for(int shift=0;shift<64;shift+=7) {
            if(p[0]>=b.length) throw new IllegalArgumentException("truncated");
            int v=b[p[0]++]&255;n|=(long)(v&127)<<shift;
            if((v&128)==0)return n;
        }
        throw new IllegalArgumentException("varint overflow");
    }
    static List<Field> fields(byte[] b) {
        List<Field> result=new ArrayList<>();int[] p={0};
        while(p[0]<b.length) {
            int begin=p[0];long key=read(b,p);int number=(int)(key>>>3),wire=(int)(key&7);
            if(number<=0)throw new IllegalArgumentException("field zero");
            byte[] data=null;
            if(wire==0)read(b,p);
            else if(wire==1)p[0]+=8;
            else if(wire==5)p[0]+=4;
            else if(wire==2) {
                long length=read(b,p);
                if(length<0 || length>b.length-p[0])throw new IllegalArgumentException("length");
                int end=p[0]+(int)length;data=Arrays.copyOfRange(b,p[0],end);p[0]=end;
            } else throw new IllegalArgumentException("unsupported wire type");
            if(p[0]>b.length)throw new IllegalArgumentException("truncated fixed");
            result.add(new Field(number,wire,data,Arrays.copyOfRange(b,begin,p[0])));
        }
        return result;
    }
    static final class Field {
        final int number,wire;final byte[] value,raw;
        Field(int n,int w,byte[] v,byte[] r){number=n;wire=w;value=v;raw=r;}
    }
}
