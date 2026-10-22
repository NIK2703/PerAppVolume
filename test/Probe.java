import android.media.audiofx.LoudnessEnhancer;
import android.os.Process;

public class Probe {
    public static void main(String[] args) throws Exception {
        int session = args.length > 0 ? Integer.parseInt(args[0]) : 817;
        int gain = args.length > 1 ? Integer.parseInt(args[1]) : -3000;
        System.out.println("uid=" + Process.myUid() + " pid=" + Process.myPid());
        LoudnessEnhancer le = null;
        try {
            le = new LoudnessEnhancer(session);
            System.out.println("created, enabled=" + le.getEnabled());
            int r = le.setEnabled(true);
            System.out.println("enable ret=" + r + " enabled=" + le.getEnabled());
            le.setTargetGain(gain);
            System.out.println("target=" + le.getTargetGain());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t);
            t.printStackTrace(System.out);
        }
        if (le != null) {
            Thread.sleep(60000);
            le.release();
            System.out.println("released");
        }
    }
}
