import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import java.io.File;
public class SettingsDexProbe {
 public static void main(String[] args)throws Exception {
  var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.getDefault());
  for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses()) {
   if(!c.getType().equals("Lapp/morphe/extension/shared/settings/preference/AbstractPreferenceFragment;"))continue;
   for(Method m:c.getMethods()) {
    if(!m.getName().matches("initialize|lambda\\$new\\$4|onCreateView|onPreferenceTreeClick"))continue;
    System.out.println("METHOD "+m.getName()+m.getParameterTypes()+m.getReturnType()+" regs="+m.getImplementation().getRegisterCount());
    int at=0;
    for(Instruction i:m.getImplementation().getInstructions()) {
     String regs="";
     if(i instanceof RegisterRangeInstruction x)regs="v"+x.getStartRegister()+"..v"+(x.getStartRegister()+x.getRegisterCount()-1);
     else if(i instanceof FiveRegisterInstruction x)regs="count="+x.getRegisterCount()+" v"+x.getRegisterC()+",v"+x.getRegisterD()+",v"+x.getRegisterE()+",v"+x.getRegisterF()+",v"+x.getRegisterG();
     else if(i instanceof ThreeRegisterInstruction x)regs="v"+x.getRegisterA()+",v"+x.getRegisterB()+",v"+x.getRegisterC();
     else if(i instanceof TwoRegisterInstruction x)regs="v"+x.getRegisterA()+",v"+x.getRegisterB();
     else if(i instanceof OneRegisterInstruction x)regs="v"+x.getRegisterA();
     String ref=i instanceof ReferenceInstruction x?x.getReference().toString():"";
     System.out.printf("  0x%X %s %s %s%n",at,i.getOpcode(),regs,ref);at+=i.getCodeUnits();
    }
   }
  }
 }
}