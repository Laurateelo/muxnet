package lab.verification;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunnerUtil;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import java.awt.Frame;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** CI-only observer/driver; installed in a temporary IDE profile, never in the APK. */
public final class LabStudioAutomation implements StartupActivity, DumbAware {
    private final AtomicBoolean requested = new AtomicBoolean();
    private boolean syncRequested;
    private String previousState = "";
    private static Path evidence() { return Path.of(System.getProperty("lab.evidence.path")); }
    private static synchronized void event(String name, String detail) {
        try {
            Files.createDirectories(evidence());
            Files.writeString(evidence().resolve("ide-events.txt"),
                Instant.now()+" "+name+" "+detail.replace('\n',' ')+'\n', StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) { exception.printStackTrace(); }
    }
    @Override public void runActivity(Project project) {
        event("PROJECT_OPENED", project.getBasePath());
        project.getMessageBus().connect(project).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
                if(!env.getRunProfile().getClass().getName().endsWith(".AndroidRunConfiguration")) return;
                event("PROCESS_STARTED", executorId+" "+env.getRunProfile().getName()+" "+handler.getClass().getName());
            }
            @Override public void processNotStarted(String executorId, ExecutionEnvironment env) {
                event("PROCESS_NOT_STARTED", executorId+" "+env.getRunProfile().getName());
            }
        });
        Thread worker = new Thread(() -> {
            for (int i=0;i<150 && !project.isDisposed() && !requested.get();i++) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                ApplicationManager.getApplication().invokeLater(() -> inspectAndRun(project));
            }
            if (!requested.get()) event("TIMEOUT", "No ready Android run configuration was found.");
        }, "lab-ide-verification");
        worker.setDaemon(true);
        worker.start();
    }
    private void inspectAndRun(Project project) {
        if (project.isDisposed() || requested.get()) return;
        try {
            Frame frame=WindowManager.getInstance().getFrame(project);
            if(frame!=null) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            Class<?> type=Class.forName("com.android.tools.idea.gradle.project.sync.GradleSyncState");
            Object state=type.getMethod("getInstance", Project.class).invoke(null,project);
            boolean syncing=(Boolean)type.getMethod("isSyncInProgress").invoke(state);
            String result=String.valueOf(type.getMethod("getLastSyncResult").invoke(state));
            boolean dumb=DumbService.isDumb(project);
            String description="syncing="+syncing+" result="+result+" indexing="+dumb;
            if(!description.equals(previousState)){event("SYNC_STATE",description);previousState=description;}
            if(syncing || dumb) return;
            if(!(result.equalsIgnoreCase("SUCCESS") || result.equalsIgnoreCase("SKIPPED"))){
                if(!syncRequested){
                    syncRequested=true;
                    AnAction action=ActionManager.getInstance().getAction("Android.SyncProject");
                    if(action==null) throw new IllegalStateException("Android.SyncProject action unavailable");
                    DataContext context=id -> CommonDataKeys.PROJECT.is(id)?project:null;
                    event("SYNC_REQUESTED", "Android.SyncProject");
                    action.actionPerformed(AnActionEvent.createFromAnAction(action,null,ActionPlaces.UNKNOWN,context));
                }
                return;
            }
            if(!Boolean.getBoolean("lab.run.enabled")){requested.set(true);event("SYNC_READY",result);return;}
            RunManager manager=RunManager.getInstance(project);
            for(RunnerAndConfigurationSettings config:manager.getAllSettings()) {
                String className=config.getConfiguration().getClass().getName();
                if(!className.endsWith(".AndroidRunConfiguration")) continue;
                requested.set(true);
                manager.setSelectedConfiguration(config);
                VirtualFile source=LocalFileSystem.getInstance().findFileByPath(
                    project.getBasePath()+"/app/src/main/java/cn/edu/hnie/shoppingcart/MainActivity.java");
                if(source!=null) FileEditorManager.getInstance(project).openFile(source,true);
                event("RUN_REQUESTED",config.getName()+" "+className);
                ProgramRunnerUtil.executeConfiguration(config,DefaultRunExecutor.getRunExecutorInstance());
                return;
            }
        } catch(Throwable exception){
            String failure=exception.getClass().getName()+": "+exception.getMessage();
            if(!failure.equals(previousState)){event("DRIVER_ERROR",failure);previousState=failure;}
        }
    }
}
