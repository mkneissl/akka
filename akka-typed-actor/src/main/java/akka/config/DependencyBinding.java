/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class DependencyBinding {
  private final Class intf;
  private final Object target;

  public DependencyBinding(final Class intf, final Object target) {
    this.intf = intf;
    this.target = target;
  }
  public Class getInterface() {
    return intf;
  }
  public Object getTarget() {
    return target;
  }
}
