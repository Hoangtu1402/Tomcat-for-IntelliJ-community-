package io.github.hoangtu.tomcat.jps;

import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.JpsElement;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension;
import org.jetbrains.jps.model.serialization.artifact.JpsPackagingElementSerializer;
import org.jetbrains.jps.model.serialization.facet.JpsFacetConfigurationSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Teaches the external build (JPS) about the "web" facet and the "javaee-facet-resources" packaging element,
 * so WAR artifacts created by IntelliJ IDEA Ultimate (or by this plugin) can be built in Community Edition.
 */
public final class WebModelSerializerExtension extends JpsModelSerializerExtension {
  public static final String WEB_FACET_TYPE = "web";
  public static final String FACET_RESOURCES_ELEMENT = "javaee-facet-resources";

  @Override
  public @NotNull List<? extends JpsFacetConfigurationSerializer<?>> getFacetConfigurationSerializers() {
    return List.of(new WebFacetSerializer());
  }

  @Override
  public @NotNull List<? extends JpsPackagingElementSerializer<?>> getPackagingElementSerializers() {
    return List.of(new FacetResourcesSerializer());
  }

  private static final class WebFacetSerializer extends JpsFacetConfigurationSerializer<JpsWebFacetsElement> {
    WebFacetSerializer() {
      super(JpsWebFacetsElement.ROLE, WEB_FACET_TYPE, null);
    }

    @Override
    public JpsWebFacetsElement loadExtension(Element configurationElement, String facetName, JpsModule module, JpsElement parentFacet) {
      JpsWebFacetsElement existing = module.getContainer().getChild(JpsWebFacetsElement.ROLE);
      if (existing == null) {
        existing = module.getContainer().setChild(JpsWebFacetsElement.ROLE, new JpsWebFacetsElement());
      }
      existing.addFacet(facetName, readRoots(configurationElement));
      return existing;
    }

    @Override
    protected JpsWebFacetsElement loadExtension(@NotNull Element facetConfigurationElement, String name, JpsElement parent, JpsModule module) {
      JpsWebFacetsElement element = new JpsWebFacetsElement();
      element.addFacet(name, readRoots(facetConfigurationElement));
      return element;
    }

    private static List<JpsWebRoot> readRoots(Element configuration) {
      List<JpsWebRoot> result = new ArrayList<>();
      Element webRoots = configuration.getChild("webroots");
      if (webRoots != null) {
        for (Element root : webRoots.getChildren("root")) {
          String url = root.getAttributeValue("url");
          if (!StringUtil.isEmpty(url)) {
            result.add(new JpsWebRoot(url, root.getAttributeValue("relative")));
          }
        }
      }
      return result;
    }
  }

  private static final class FacetResourcesSerializer extends JpsPackagingElementSerializer<JpsWebFacetResourcesElement> {
    FacetResourcesSerializer() {
      super(FACET_RESOURCES_ELEMENT, JpsWebFacetResourcesElement.class);
    }

    @Override
    public JpsWebFacetResourcesElement load(Element element) {
      return new JpsWebFacetResourcesElement(element.getAttributeValue("facet"));
    }
  }
}
