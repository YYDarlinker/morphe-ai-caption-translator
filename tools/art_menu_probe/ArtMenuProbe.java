/** Invokes the actual patched class, rather than merely loading its method metadata.
 * Run on Android ART with the patched APK on CLASSPATH. No Activity or network is needed.
 */
public final class ArtMenuProbe {
    public static void main(String[] args) {
        try {
            Class<?> menu = Class.forName("app.yydarlinker.deepseekcaptions.CaptionQuickToggle");
            java.lang.reflect.Method enter = menu.getDeclaredMethod("onMenu", Object.class, int.class);
            for (int index : new int[] {0, 3}) {
                Object result = enter.invoke(null, new Object[] {null, index});
                if (!Integer.valueOf(index).equals(result)) {
                    throw new AssertionError("Missing-Activity entry must preserve group index: " + result);
                }
                System.out.println("ART_MENU_ENTRY_PASS index=" + index);
            }
            System.out.println("ART_MENU_INVOKE_PASS");
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
