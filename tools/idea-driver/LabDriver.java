package lab.evidence;

import com.intellij.execution.*;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.*;
import com.intellij.util.ui.tree.TreeUtil;
import java.awt.Frame;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** Runs only in a temporary IDE profile; never included in the application JAR. */
public final class LabDriver implements StartupActivity, DumbAware {
  private RunnerAndConfigurationSettings settings;
  private String lastCommand="";
  private static final Path OUT=Path.of("D:/lab3-ci/evidence");
  private static synchronized void record(String event,String detail){
    try{Files.createDirectories(OUT);Files.writeString(OUT.resolve("ide-events.txt"),Instant.now()+" "+event+" "+detail.replace('\n',' ')+'\n',StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    catch(Exception e){e.printStackTrace();}
  }
  @Override public void runActivity(Project project){
    record("PROJECT_OPENED",project.getBasePath());
    project.getMessageBus().connect(project).subscribe(ExecutionManager.EXECUTION_TOPIC,new ExecutionListener(){
      @Override public void processStarted(String executorId,ExecutionEnvironment environment,ProcessHandler handler){
        if(!environment.getRunProfile().getName().equals("DemoApplication"))return;
        String pid="unknown";
        try{Object process=handler.getClass().getMethod("getProcess").invoke(handler);if(process instanceof Process)pid=Long.toString(((Process)process).pid());}catch(Exception ignored){}
        record("PROCESS_STARTED","pid="+pid+" executor="+executorId+" configuration="+environment.getRunProfile().getName());
        ApplicationManager.getApplication().invokeLater(()->show(project,"Run"));
      }
      @Override public void processTerminated(String executorId,ExecutionEnvironment environment,ProcessHandler handler,int code){
        record("PROCESS_TERMINATED",environment.getRunProfile().getName()+" exit="+code);
      }
      @Override public void processNotStarted(String executorId,ExecutionEnvironment environment){record("PROCESS_NOT_STARTED",environment.getRunProfile().getName());}
    });
    Thread worker=new Thread(()->{
      for(int i=0;i<3900&&!project.isDisposed();i++){
        try{
          Thread.sleep(1000);
          Path commandFile=Path.of("D:/lab3-ci/ide-command.txt");
          if(!Files.exists(commandFile))continue;
          String command=Files.readString(commandFile).trim();
          if(command.isEmpty()||command.equals(lastCommand))continue;
          lastCommand=command;
          ApplicationManager.getApplication().invokeLater(()->perform(project,command));
        }catch(Exception e){record("DRIVER_ERROR",e.toString());}
      }
    },"lab3-ide-evidence");
    worker.setDaemon(true);worker.start();
  }
  private static void show(Project p,String id){
    Frame frame=WindowManager.getInstance().getFrame(p);if(frame!=null){frame.setExtendedState(Frame.MAXIMIZED_BOTH);frame.toFront();}
    ToolWindow w=ToolWindowManager.getInstance(p).getToolWindow(id);if(w!=null)w.activate(null);
  }
  private void perform(Project project,String command){
    try{
      String operation=command.substring(command.indexOf(':')+1);
      if(operation.equals("prepare")){
        if(DumbService.isDumb(project)){lastCommand="";return;}
        RunManager manager=RunManager.getInstance(project);
        settings=manager.createConfiguration("DemoApplication",ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        ApplicationConfiguration config=(ApplicationConfiguration)settings.getConfiguration();
        com.intellij.openapi.module.Module[] modules=ModuleManager.getInstance(project).getModules();
        if(modules.length==0)throw new IllegalStateException("No project module");
        config.setModule(modules[0]);config.setMainClassName("com.example.demo.DemoApplication");
        config.setWorkingDirectory(project.getBasePath());config.setVMParameters("-Dfile.encoding=UTF-8");
        manager.addConfiguration(settings);manager.setSelectedConfiguration(settings);
        var source=LocalFileSystem.getInstance().findFileByPath(project.getBasePath()+"/src/main/java/com/example/demo/DemoApplication.java");
        if(source!=null)FileEditorManager.getInstance(project).openFile(source,true);
        show(project,"Project");ProjectView.getInstance(project).changeView("ProjectPane");
        var pane=ProjectView.getInstance(project).getCurrentProjectViewPane();if(pane!=null)TreeUtil.expandAll(pane.getTree());
        record("CONFIGURATION_READY",config.getName()+" module="+modules[0].getName());
      }else if(operation.equals("build-run")){
        if(settings==null)throw new IllegalStateException("Prepare configuration first");
        record("IDE_BUILD_REQUESTED","CompilerManager.make");
        CompilerManager.getInstance(project).make((aborted,errors,warnings,context)->{
          record("IDE_BUILD_FINISHED","aborted="+aborted+" errors="+errors+" warnings="+warnings);
          if(!aborted&&errors==0){record("IDE_RUN_REQUESTED","DemoApplication");ProgramRunnerUtil.executeConfiguration(settings,DefaultRunExecutor.getRunExecutorInstance());}
        });
      }else if(operation.equals("project")){
        show(project,"Project");var pane=ProjectView.getInstance(project).getCurrentProjectViewPane();if(pane!=null)TreeUtil.expandAll(pane.getTree());
      }else if(operation.equals("run-window")){show(project,"Run");}
      else{throw new IllegalArgumentException("Unsupported IDE operation "+operation);}
    }catch(Throwable e){record("DRIVER_ERROR",e.toString());}
  }
}
