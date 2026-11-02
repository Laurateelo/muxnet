package lab.verification;
import java.awt.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import javax.swing.*;

/** Declines the observed first-run usage-statistics dialog in the disposable CI IDE. */
public final class LabConsentAgent {
    public static void premain(String ignored, Instrumentation instrumentation) {
        Thread observer=new Thread(() -> {
            for(int i=0;i<180;i++) {
                try { Thread.sleep(1000); } catch(InterruptedException e) { return; }
                SwingUtilities.invokeLater(() -> {
                    for(Window window:Window.getWindows()) if(window.isShowing()) decline(window);
                });
            }
        },"lab-decline-telemetry");
        observer.setDaemon(true);observer.start();
    }
    private static void decline(Component component) {
        if(component instanceof AbstractButton) {
            AbstractButton button=(AbstractButton)component;
            if("Don't send".equals(button.getText()) && button.isShowing() && button.isEnabled()) {
                button.doClick();
                try {
                    Path target=Path.of(System.getProperty("lab.evidence.path"),"consent-action.txt");
                    Files.writeString(target,Instant.now()+" Clicked observed Don't send button\n",StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                } catch(Exception exception){exception.printStackTrace();}
                return;
            }
        }
        if(component instanceof Container) for(Component child:((Container)component).getComponents()) decline(child);
    }
}
