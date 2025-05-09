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
package org.apache.solr.metrics;

import com.codahale.metrics.MetricRegistry;
import java.util.Objects;
import org.apache.solr.core.SolrInfoBean;

/** Wraps meta-data for a metric. */
public final class SolrMetricInfo {
  public final String name;
  public final String scope;
  public final SolrInfoBean.Category category;

  /**
   * Creates a new instance of {@link SolrMetricInfo}.
   *
   * @param category the category of the metric (e.g. `QUERY`)
   * @param scope the scope of the metric (e.g. `/admin/ping`)
   * @param name the name of the metric (e.g. `Requests`)
   */
  public SolrMetricInfo(SolrInfoBean.Category category, String scope, String name) {
    this.name = name;
    this.scope = scope;
    this.category = category;
  }

  public static SolrMetricInfo of(String fullName) {
    if (fullName == null || fullName.isEmpty()) {
      return null;
    }
    String[] names = fullName.split("\\.");
    if (names.length < 2) { // not a valid info
      return null;
    }
    // check top-level name for valid category
    SolrInfoBean.Category category;
    try {
      category = SolrInfoBean.Category.valueOf(names[0]);
    } catch (IllegalArgumentException e) { // not a valid category
      return null;
    }
    String scope;
    String name;
    if (names.length == 2) {
      scope = null;
      name = fullName.substring(names[0].length() + 1);
    } else {
      scope = names[1];
      name = fullName.substring(names[0].length() + names[1].length() + 2);
    }
    return new SolrMetricInfo(category, scope, name);
  }

  /**
   * Returns the metric name defined by this object. For example, if the name is `Requests`, scope
   * is `/admin/ping`, and category is `QUERY`, then the metric name is
   * `QUERY./admin/ping.Requests`.
   *
   * @return the metric name defined by this object
   */
  public String getMetricName() {
    return MetricRegistry.name(category.toString(), scope, name);
  }

  @Override
  public String toString() {
    return "SolrMetricInfo{"
        + "name='"
        + name
        + '\''
        + ", scope='"
        + scope
        + '\''
        + ", category="
        + category
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SolrMetricInfo that)) return false;

    return Objects.equals(name, that.name)
        && Objects.equals(scope, that.scope)
        && category == that.category;
  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (scope != null ? scope.hashCode() : 0);
    result = 31 * result + (category != null ? category.hashCode() : 0);
    return result;
  }
}
