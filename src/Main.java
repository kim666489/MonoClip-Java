import java.nio.file.*;

public class Main {
    public static void main(String[] args) {
        try {
            MonoClip mc = new MonoClip();
            mc.loadRulePath(Paths.get("./example/Rule.json"));
            mc.DebugAst_Enable = true;
            mc.parse("print \"Hello World\"");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
