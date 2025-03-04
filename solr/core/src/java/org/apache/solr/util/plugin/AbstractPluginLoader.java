/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.util.plugin;

import static org.apache.solr.common.params.CommonParams.NAME;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.solr.common.ConfigNode;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An abstract super class that manages standard solr-style plugin configuration.
 *
 * @since solr 1.3
 */
public abstract class AbstractPluginLoader<T> {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final String type;
  private final boolean preRegister;
  private final boolean requireName;
  private final Class<T> pluginClassType;

  /**
   * @param type is the 'type' name included in error messages.
   * @param preRegister if true, this will first register all Plugins, then it will initialize them.
   */
  public AbstractPluginLoader(
      String type, Class<T> pluginClassType, boolean preRegister, boolean requireName) {
    this.type = type;
    this.pluginClassType = pluginClassType;
    this.preRegister = preRegister;
    this.requireName = requireName;
  }

  public AbstractPluginLoader(String type, Class<T> pluginClassType) {
    this(type, pluginClassType, false, true);
  }

  /** Where to look for classes */
  protected String[] getDefaultPackages() {
    return new String[] {};
  }

  /**
   * Create a plugin from an XML configuration. Plugins are defined using:
   *
   * <pre class="prettyprint">{@code
   * <plugin name="name1" class="solr.ClassName">
   *      ...
   * </plugin>
   * }</pre>
   *
   * @param name - The registered name. In the above example: "name1"
   * @param className - class name for requested plugin. In the above example: "solr.ClassName"
   * @param node - the XML node defining this plugin
   */
  protected T create(SolrClassLoader loader, String name, String className, ConfigNode node)
      throws Exception {
    return loader.newInstance(className, pluginClassType, getDefaultPackages());
  }

  /**
   * Register a plugin with a given name.
   *
   * @return The plugin previously registered to this name, or null
   */
  protected abstract T register(String name, T plugin) throws Exception;

  /**
   * Initialize the plugin.
   *
   * @param plugin - the plugin to initialize
   * @param node - the XML node defining this plugin
   */
  protected abstract void init(T plugin, ConfigNode node) throws Exception;

  /**
   * Initializes and registers each plugin in the list. Given a NodeList from XML in the form:
   *
   * <pre class="prettyprint">{@code
   * <plugins>
   *    <plugin name="name1" class="solr.ClassName" >
   *      ...
   *    </plugin>
   *    <plugin name="name2" class="solr.ClassName" >
   *      ...
   *    </plugin>
   * </plugins>
   * }</pre>
   *
   * This will initialize and register each plugin from the list. A class will be generated for each
   * class name and registered to the given name.
   *
   * <p>If 'preRegister' is true, each plugin will be registered *before* it is initialized This may
   * be useful for implementations that need to inspect other registered plugins at startup.
   *
   * <p>One (and only one) plugin may declare itself to be the 'default' plugin using:
   *
   * <pre class="prettyprint">{@code
   * <plugin name="name2" class="solr.ClassName" default="true">
   * }</pre>
   *
   * If a default element is defined, it will be returned from this function.
   */
  public T load(SolrClassLoader loader, List<ConfigNode> nodes) {
    List<PluginInitInfo> info = new ArrayList<>();
    T defaultPlugin = null;

    if (nodes != null) {
      for (ConfigNode node : nodes) {
        String name = null;
        try {
          name = requireName ? node.attrRequired(NAME, type) : node.attr(NAME);
          String className = node.attr("class");
          String defaultStr = node.attr("default");

          if (Objects.isNull(className) && Objects.isNull(name)) {
            throw new RuntimeException(type + ": missing mandatory attribute 'class' or 'name'");
          }

          T plugin = create(loader, name, className, node);
          if (log.isDebugEnabled()) {
            log.debug("created {}: {}", ((name != null) ? name : ""), plugin.getClass().getName());
          }

          // Either initialize now or wait till everything has been registered
          if (preRegister) {
            info.add(new PluginInitInfo(plugin, node));
          } else {
            init(plugin, node);
          }

          T old = register(name, plugin);
          if (old != null && !(name == null && !requireName)) {
            throw new SolrException(
                ErrorCode.SERVER_ERROR,
                "Multiple " + type + " registered to the same name: " + name + " ignoring: " + old);
          }

          if (defaultStr != null && Boolean.parseBoolean(defaultStr)) {
            if (defaultPlugin != null) {
              throw new SolrException(
                  ErrorCode.SERVER_ERROR,
                  "Multiple default " + type + " plugins: " + defaultPlugin + " AND " + name);
            }
            defaultPlugin = plugin;
          }
        } catch (Exception ex) {
          SolrException e =
              new SolrException(
                  ErrorCode.SERVER_ERROR,
                  "Plugin init failure for "
                      + type
                      + (null != name ? (" \"" + name + "\"") : "")
                      + ": "
                      + ex.getMessage(),
                  ex);
          throw e;
        }
      }
    }

    // If everything needs to be registered *first*, this will initialize later
    for (PluginInitInfo pinfo : info) {
      try {
        init(pinfo.plugin, pinfo.node);
      } catch (Exception ex) {
        SolrException e =
            new SolrException(
                ErrorCode.SERVER_ERROR, "Plugin Initializing failure for " + type, ex);
        throw e;
      }
    }
    return defaultPlugin;
  }

  /**
   * Initializes and registers a single plugin.
   *
   * <p>Given a NodeList from XML in the form:
   *
   * <pre class="prettyprint">{@code
   * <plugin name="name1" class="solr.ClassName" > ... </plugin>
   * }</pre>
   *
   * This will initialize and register a single plugin. A class will be generated for the plugin and
   * registered to the given name.
   *
   * <p>If 'preRegister' is true, the plugin will be registered *before* it is initialized This may
   * be useful for implementations that need to inspect other registered plugins at startup.
   *
   * <p>The created class for the plugin will be returned from this function.
   */
  public T loadSingle(SolrClassLoader loader, ConfigNode node) {
    List<PluginInitInfo> info = new ArrayList<>();
    T plugin = null;

    try {
      String name = requireName ? node.attrRequired(NAME, type) : node.attr(NAME);
      String className = node.attrRequired("class", type);
      plugin = create(loader, name, className, node);
      if (log.isDebugEnabled()) {
        log.debug("created {}: {}", name, plugin.getClass().getName());
      }

      // Either initialize now or wait till everything has been registered
      if (preRegister) {
        info.add(new PluginInitInfo(plugin, node));
      } else {
        init(plugin, node);
      }

      T old = register(name, plugin);
      if (old != null && !(name == null && !requireName)) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Multiple " + type + " registered to the same name: " + name + " ignoring: " + old);
      }

    } catch (Exception ex) {
      SolrException e =
          new SolrException(ErrorCode.SERVER_ERROR, "Plugin init failure for " + type, ex);
      throw e;
    }

    // If everything needs to be registered *first*, this will initialize later
    for (PluginInitInfo pinfo : info) {
      try {
        init(pinfo.plugin, pinfo.node);
      } catch (SolrException e) {
        throw new SolrException(
            ErrorCode.getErrorCode(e.code()), "Plugin init failure for " + type, e);
      } catch (Exception ex) {
        throw new SolrException(ErrorCode.SERVER_ERROR, "Plugin init failure for " + type, ex);
      }
    }
    return plugin;
  }

  /**
   * Internal class to hold onto initialization info so that it can be initialized after it is
   * registered.
   */
  private class PluginInitInfo {
    final T plugin;
    final ConfigNode node;

    PluginInitInfo(T plugin, ConfigNode node) {
      this.plugin = plugin;
      this.node = node;
    }
  }
}
