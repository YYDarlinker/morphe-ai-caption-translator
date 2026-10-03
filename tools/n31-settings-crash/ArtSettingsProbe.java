/** Actual Android ART verification of the delivered settings hierarchy. No install, Activity, account or API. */
public final class ArtSettingsProbe {
 public static void main(String[] args) {
  try {
   for(String name:new String[]{
     "app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment",
     "app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment",
     "app.morphe.extension.youtube.settings.preference.YouTubePreferenceFragment",
     "app.yydarlinker.deepseekcaptions.CaptionPreferenceBindings"
   }) {
    Class<?> type=Class.forName(name,true,ArtSettingsProbe.class.getClassLoader());
    java.lang.reflect.Method[] methods=type.getDeclaredMethods();
    type.getDeclaredConstructors();
    System.out.println("ART_SETTINGS_CLASS_PASS name="+name+" methods="+methods.length);
   }
   System.out.println("ART_SETTINGS_VERIFICATION_PASS");
  }catch(Throwable error){error.printStackTrace();System.exit(1);}
 }
}