import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
public class InspectDex {
 public static void main(String[] args)throws Exception {
  var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.getDefault());
  for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses()) {
   if(!c.getType().contains(args[1]))continue;
   System.out.println("CLASS "+c.getType()+" flags="+c.getAccessFlags()+" super="+c.getSuperclass());
   for(Field f:c.getFields())System.out.println("FIELD "+f.getName()+":"+f.getType()+" flags="+f.getAccessFlags());
   for(Method m:c.getMethods()) {
    System.out.println("METHOD "+m.getName()+m.getParameterTypes()+m.getReturnType()+" flags="+m.getAccessFlags());
    if(args.length>2 && !m.getName().matches(args[2]))continue;
    if(args.length<3)continue;
    if(m.getImplementation()!=null)for(Instruction ins:m.getImplementation().getInstructions()) {
     String ref=ins instanceof ReferenceInstruction?((ReferenceInstruction)ins).getReference().toString():"";
     System.out.println("  "+ins.getOpcode()+" "+ref);
    }
   }
  }
 }
}
