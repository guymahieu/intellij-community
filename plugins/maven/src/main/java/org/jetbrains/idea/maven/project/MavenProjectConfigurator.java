package org.jetbrains.idea.maven.project;

import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import org.jetbrains.idea.maven.utils.MavenLog;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MavenProjectConfigurator {
  private Project myProject;
  private ModifiableModuleModel myModuleModel;
  private MavenProjectsTree myMavenTree;
  private Map<VirtualFile, Module> myFileToModuleMapping;
  private MavenImportingSettings myImportingSettings;
  private List<ModifiableRootModel> myRootModelsToCommit = new ArrayList<ModifiableRootModel>();

  private Map<MavenProjectModel, Module> myMavenProjectToModule = new HashMap<MavenProjectModel, Module>();
  private Map<MavenProjectModel, String> myMavenProjectToModuleName = new HashMap<MavenProjectModel, String>();
  private Map<MavenProjectModel, String> myMavenProjectToModulePath = new HashMap<MavenProjectModel, String>();
  private List<Module> myCreatedModules = new ArrayList<Module>();


  public MavenProjectConfigurator(Project p,
                                  MavenProjectsTree projectsTree,
                                  Map<VirtualFile, Module> fileToModuleMapping,
                                  MavenImportingSettings importingSettings) {
    myProject = p;
    myMavenTree = projectsTree;
    myFileToModuleMapping = fileToModuleMapping;
    myImportingSettings = importingSettings;
  }

  public List<PostProjectConfigurationTask> config(final ModifiableModuleModel model, final ModulesProvider modulesProvider) {
    List<PostProjectConfigurationTask> postTasks = new ArrayList<PostProjectConfigurationTask>();

    myModuleModel = model != null ? model : ModuleManager.getInstance(myProject).getModifiableModel();
    mapModulesToMavenProjects();
    configSettings();
    deleteObsoleteModules();
    configModules(postTasks, modulesProvider);
    configModuleGroups();
    refreshResolvedArtifacts();
    if (model == null) commit();

    return postTasks;
  }

  private void refreshResolvedArtifacts() {
    // We have to refresh all the resolved artifacts manually in order to
    // update all the VirtualFilePointers. It is not enough to call
    // VirtualFileManager.refresh() since the newly created files will be only
    // picked by FS when FileWathcer finishes its work. And in the case of import
    // it doesn't finish in time.
    // I couldn't manage to write a test for this since behaviour of VirtualFileManager
    // and FileWatcher differs from real-life execution.

    List<MavenArtifact> artifacts = new ArrayList<MavenArtifact>();
    for (MavenProjectModel each : getMavenProjectsToConfigure()) {
      artifacts.addAll(each.getDependencies());
    }

    List<File> files = new ArrayList<File>();
    for (MavenArtifact each : artifacts) {
      if (each.isResolved()) files.add(each.getFile());
    }

    LocalFileSystem.getInstance().refreshIoFiles(files);
  }

  private void mapModulesToMavenProjects() {
    for (MavenProjectModel each : getMavenProjectsToConfigure()) {
      myMavenProjectToModule.put(each, myFileToModuleMapping.get(each.getFile()));
    }
    MavenModuleNameMapper.map(myMavenTree,
                              myMavenProjectToModule,
                              myMavenProjectToModuleName,
                              myMavenProjectToModulePath,
                              myImportingSettings.getDedicatedModuleDir());
  }

  private void configSettings() {
    ((ProjectEx)myProject).setSavePathsRelative(true);
  }

  private void deleteObsoleteModules() {
    List<Module> obsolete = collectObsoleteModules();
    if (obsolete.isEmpty()) return;

    MavenProjectsManager.getInstance(myProject).setRegularModules(obsolete);

    String formatted = StringUtil.join(obsolete, new Function<Module, String>() {
      public String fun(Module m) {
        return "'" + m.getName() + "'";
      }
    }, "\n");

    int result = Messages.showYesNoDialog(myProject,
                                          ProjectBundle.message("maven.import.message.delete.obsolete", formatted),
                                          ProjectBundle.message("maven.tab.importing"),
                                          Messages.getQuestionIcon());
    if (result == 1) return;// NO

    for (Module each : obsolete) {
      myModuleModel.disposeModule(each);
    }
  }

  private List<Module> collectObsoleteModules() {
    List<Module> remainingModules = new ArrayList<Module>();
    Collections.addAll(remainingModules, ModuleManager.getInstance(myProject).getModules());
    remainingModules.removeAll(myMavenProjectToModule.values());

    List<Module> obsolete = new ArrayList<Module>();
    for (Module each : remainingModules) {
      if (MavenProjectsManager.getInstance(myProject).isMavenizedModule(each)) {
        obsolete.add(each);
      }
    }
    return obsolete;
  }

  private void configModules(List<PostProjectConfigurationTask> postTasks, final ModulesProvider modulesProvider) {
    List<MavenProjectModel> projects = getMavenProjectsToConfigure();
    Set<MavenProjectModel> projectsWithNewlyCreatedModules = new HashSet<MavenProjectModel>();

    for (MavenProjectModel each : projects) {
      if (ensureModuleCreated(each)) {
        projectsWithNewlyCreatedModules.add(each);
      }
    }

    LinkedHashMap<Module, MavenModuleConfigurator> configurators = new LinkedHashMap<Module, MavenModuleConfigurator>();
    for (MavenProjectModel each : projects) {
      Module module = myMavenProjectToModule.get(each);
      MavenModuleConfigurator c = createModuleConfigurator(module, each, modulesProvider);
      configurators.put(module, c);

      c.config(projectsWithNewlyCreatedModules.contains(each));
    }

    for (MavenProjectModel each : projects) {
      configurators.get(myMavenProjectToModule.get(each)).preConfigFacets();
    }

    for (MavenProjectModel each : projects) {
      configurators.get(myMavenProjectToModule.get(each)).configFacets(postTasks);
    }

    for (MavenModuleConfigurator each : configurators.values()) {
      myRootModelsToCommit.add(each.getRootModel());
    }

    ArrayList<Module> modules = new ArrayList<Module>(myMavenProjectToModule.values());
    MavenProjectsManager.getInstance(myProject).setMavenizedModules(modules);
  }

  private List<MavenProjectModel> getMavenProjectsToConfigure() {
    List<MavenProjectModel> result = new ArrayList<MavenProjectModel>();
    for (MavenProjectModel each : myMavenTree.getProjects()) {
      if (!shouldCreateModuleFor(each)) continue;
      result.add(each);
    }
    return result;
  }

  private boolean shouldCreateModuleFor(MavenProjectModel project) {
    return myImportingSettings.isCreateModulesForAggregators() || !project.isAggregator();
  }

  private boolean ensureModuleCreated(MavenProjectModel project) {
    if (myMavenProjectToModule.get(project) != null) return false;

    String path = myMavenProjectToModulePath.get(project);
    // for some reason newModule opens the existing iml file, so we
    // have to remove it beforehand.
    removeExistingIml(path);
    final Module module = myModuleModel.newModule(path, StdModuleTypes.JAVA);
    myMavenProjectToModule.put(project, module);
    myCreatedModules.add(module);
    return true;
  }

  private MavenModuleConfigurator createModuleConfigurator(Module module,
                                                           MavenProjectModel mavenProject,
                                                           ModulesProvider modulesProvider) {
    return new MavenModuleConfigurator(module,
                                       myModuleModel,
                                       myMavenTree,
                                       mavenProject,
                                       myMavenProjectToModuleName,
                                       myImportingSettings,
                                       modulesProvider
    );
  }

  private void removeExistingIml(String path) {
    VirtualFile existingFile = LocalFileSystem.getInstance().findFileByPath(path);
    if (existingFile == null) return;
    try {
      existingFile.delete(this);
    }
    catch (IOException ignore) {
    }
  }

  private void configModuleGroups() {
    if (!myImportingSettings.isCreateModuleGroups()) return;

    final Stack<String> groups = new Stack<String>();
    final boolean createTopLevelGroup = myMavenTree.getRootProjects().size() > 1;

    myMavenTree.visit(new MavenProjectsTree.SimpleVisitor() {
      int depth = 0;

      public void visit(MavenProjectModel each) {
        depth++;

        String name = myMavenProjectToModuleName.get(each);

        if (shouldCreateGroup(each)) {
          groups.push(ProjectBundle.message("module.group.name", name));
        }

        if (!shouldCreateModuleFor(each)) return;

        Module module = myModuleModel.findModuleByName(name);
        if (module == null) {
          // todo: IDEADEV-30669 hook
          String message = "Module " + name + "not found.";
          message += "\nmavenProject="+each.getFile();
          module = myMavenProjectToModule.get(each);
          message += "\nmyMavenProjectToModule=" + (module == null ? null : module.getName());
          message += "\nmyMavenProjectToModuleName=" +myMavenProjectToModuleName.get(each);
          message += "\nmyMavenProjectToModulePath=" +myMavenProjectToModulePath.get(each);
          MavenLog.LOG.warn(message);
          return;
        }

        myModuleModel.setModuleGroupPath(module, groups.isEmpty() ? null : groups.toArray(new String[groups.size()]));
      }

      public void leave(MavenProjectModel each) {
        if (shouldCreateGroup(each)) {
          groups.pop();
        }
        depth--;
      }

      private boolean shouldCreateGroup(MavenProjectModel node) {
        return !myMavenTree.getModules(node).isEmpty()
               && (createTopLevelGroup || depth > 1);
      }
    });
  }

  private void commit() {
    ModifiableRootModel[] rootModels = myRootModelsToCommit.toArray(new ModifiableRootModel[myRootModelsToCommit.size()]);
    ProjectRootManager.getInstance(myProject).multiCommit(myModuleModel, rootModels);
  }

  public List<Module> getCreatedModules() {
    return myCreatedModules;
  }
}
