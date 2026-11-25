package io.github.hoangtu.tomcat.jps;

/** A web resource directory of a "web" facet, mapped to a path inside the deployed application. */
public final class JpsWebRoot {
  public final String url;
  public final String relativePath;

  public JpsWebRoot(String url, String relativePath) {
    this.url = url;
    this.relativePath = relativePath == null || relativePath.isEmpty() ? "/" : relativePath;
  }
}
