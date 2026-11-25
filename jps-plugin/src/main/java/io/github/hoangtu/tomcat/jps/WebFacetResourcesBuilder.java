package io.github.hoangtu.tomcat.jps;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.jps.incremental.artifacts.builders.LayoutElementBuilderService;
import org.jetbrains.jps.incremental.artifacts.instructions.ArtifactCompilerInstructionCreator;
import org.jetbrains.jps.incremental.artifacts.instructions.ArtifactInstructionsBuilderContext;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.util.JpsPathUtil;

import java.io.File;

/** Generates copy instructions for {@link JpsWebFacetResourcesElement} during the artifact build. */
public final class WebFacetResourcesBuilder extends LayoutElementBuilderService<JpsWebFacetResourcesElement> {
  public WebFacetResourcesBuilder() {
    super(JpsWebFacetResourcesElement.class);
  }

  @Override
  public void generateInstructions(JpsWebFacetResourcesElement element,
                                   ArtifactCompilerInstructionCreator instructionCreator,
                                   ArtifactInstructionsBuilderContext builderContext) {
    String facetId = element.getFacetId();
    if (facetId == null) return;
    String[] parts = facetId.split("/", 3);
    String moduleName = parts[0];
    String facetName = parts.length == 3 ? parts[2] : "Web";

    JpsModel model = builderContext.getModel();
    JpsModule module = null;
    for (JpsModule m : model.getProject().getModules()) {
      if (m.getName().equals(moduleName)) {
        module = m;
        break;
      }
    }
    if (module == null) return;
    JpsWebFacetsElement facets = module.getContainer().getChild(JpsWebFacetsElement.ROLE);
    if (facets == null) return;

    for (JpsWebRoot root : facets.getWebRoots(facetName)) {
      File dir = JpsPathUtil.urlToFile(root.url);
      if (!dir.isDirectory()) continue;
      String relative = StringUtil.trimStart(root.relativePath, "/");
      ArtifactCompilerInstructionCreator creator =
        relative.isEmpty() ? instructionCreator : instructionCreator.subFolderByRelativePath(relative);
      creator.addDirectoryCopyInstructions(dir, null, creator.getInstructionsBuilder().createCopyingHandler(dir, element, creator));
    }
  }
}
