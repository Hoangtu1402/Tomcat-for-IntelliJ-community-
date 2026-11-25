package io.github.hoangtu.tomcat.jps;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.ex.JpsElementBase;
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All "web" facets of a JPS module (facet name -> web roots). */
public final class JpsWebFacetsElement extends JpsElementBase<JpsWebFacetsElement> {
  public static final JpsElementChildRoleBase<JpsWebFacetsElement> ROLE = JpsElementChildRoleBase.create("community web facets");

  private final Map<String, List<JpsWebRoot>> myFacets = new LinkedHashMap<>();

  public void addFacet(String name, List<JpsWebRoot> roots) {
    myFacets.put(name, new ArrayList<>(roots));
  }

  public List<JpsWebRoot> getWebRoots(String facetName) {
    List<JpsWebRoot> roots = myFacets.get(facetName);
    if (roots == null && myFacets.size() == 1) {
      roots = myFacets.values().iterator().next();
    }
    return roots == null ? List.of() : roots;
  }

  @Override
  public @NotNull JpsWebFacetsElement createCopy() {
    JpsWebFacetsElement copy = new JpsWebFacetsElement();
    copy.myFacets.putAll(myFacets);
    return copy;
  }
}
