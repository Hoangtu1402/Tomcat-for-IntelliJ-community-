package io.github.hoangtu.tomcat.jps;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.artifact.elements.JpsPackagingElement;
import org.jetbrains.jps.model.ex.JpsElementBase;

/**
 * JPS counterpart of the "javaee-facet-resources" packaging element written by IntelliJ IDEA Ultimate
 * (and by this plugin): copies the web roots of a web facet into the artifact.
 */
public final class JpsWebFacetResourcesElement extends JpsElementBase<JpsWebFacetResourcesElement> implements JpsPackagingElement {
  private final String myFacetId;

  public JpsWebFacetResourcesElement(String facetId) {
    myFacetId = facetId;
  }

  /** Facet reference in the form {@code moduleName/facetTypeId/facetName}. */
  public String getFacetId() {
    return myFacetId;
  }

  @Override
  public @NotNull JpsWebFacetResourcesElement createCopy() {
    return new JpsWebFacetResourcesElement(myFacetId);
  }

  @Override
  public @NotNull JpsPackagingElement createElementCopy() {
    return createCopy();
  }
}
