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

package org.apache.solr.handler.admin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.solr.api.ApiBag.EMPTY_SPEC;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.params.CommonParams.COLLECTIONS_HANDLER_PATH;
import static org.apache.solr.common.params.CommonParams.CONFIGSETS_HANDLER_PATH;
import static org.apache.solr.common.params.CommonParams.CORES_HANDLER_PATH;
import static org.apache.solr.common.util.ValidatingJsonMap.NOT_NULL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.api.Api;
import org.apache.solr.api.ApiBag;
import org.apache.solr.api.Command;
import org.apache.solr.api.EndPoint;
import org.apache.solr.api.PayloadObj;
import org.apache.solr.api.V2HttpCall;
import org.apache.solr.api.V2HttpCall.CompositeApi;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.CommandOperation;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.JsonSchemaValidator;
import org.apache.solr.common.util.PathTrie;
import org.apache.solr.common.util.ReflectMapWriter;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.PluginBag;
import org.apache.solr.handler.PingRequestHandler;
import org.apache.solr.handler.SchemaHandler;
import org.apache.solr.handler.SolrConfigHandler;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.PermissionNameProvider;

public class TestApiFramework extends SolrTestCaseJ4 {

  public void testFramework() {
    Map<String, Object[]> calls = new HashMap<>();
    Map<String, Object> out = new HashMap<>();
    CoreContainer mockCC = getCoreContainerMock(calls, out);
    PluginBag<SolrRequestHandler> containerHandlers =
        new PluginBag<>(SolrRequestHandler.class, null, false);
    TestCollectionAPIs.MockCollectionsHandler collectionsHandler =
        new TestCollectionAPIs.MockCollectionsHandler();
    containerHandlers.put(COLLECTIONS_HANDLER_PATH, collectionsHandler);
    for (Api api : collectionsHandler.getApis()) {
      containerHandlers.getApiBag().register(api);
    }
    containerHandlers.put(CORES_HANDLER_PATH, new CoreAdminHandler(mockCC));
    containerHandlers.put(CONFIGSETS_HANDLER_PATH, new ConfigSetsHandler(mockCC));
    out.put("getRequestHandlers", containerHandlers);

    PluginBag<SolrRequestHandler> coreHandlers =
        new PluginBag<>(SolrRequestHandler.class, null, false);
    coreHandlers.put("/schema", new SchemaHandler());
    coreHandlers.put("/config", new SolrConfigHandler());
    coreHandlers.put("/admin/ping", new PingRequestHandler());

    Map<String, String> parts = new HashMap<>();
    String fullPath = "/collections/hello/shards";
    Api api = V2HttpCall.getApiInfo(containerHandlers, fullPath, "POST", fullPath, parts);
    assertNotNull(api);
    assertEquals("hello", parts.get("collection"));

    parts = new HashMap<>();
    api =
        V2HttpCall.getApiInfo(containerHandlers, "/collections/hello/shards", "POST", null, parts);
    assertConditions(api.getSpec(), Map.of("/methods[0]", "POST", "/commands/split", NOT_NULL));

    parts = new HashMap<>();
    api = V2HttpCall.getApiInfo(containerHandlers, "/collections/hello", "POST", null, parts);
    assertConditions(api.getSpec(), Map.of("/methods[0]", "POST", "/commands/modify", NOT_NULL));
    assertEquals("hello", parts.get("collection"));

    SolrQueryResponse rsp = invoke(containerHandlers, null, "/collections/_introspect", mockCC);

    Set<String> methodNames = new HashSet<>();
    methodNames.add(rsp.getValues()._getStr("/spec[0]/methods[0]"));
    methodNames.add(rsp.getValues()._getStr("/spec[1]/methods[0]"));
    methodNames.add(rsp.getValues()._getStr("/spec[2]/methods[0]"));
    assertTrue(methodNames.contains("POST"));
  }

  public void testPayload() {
    String json = "{package:pkg1, version: '0.1', files  :[a.jar, b.jar]}";
    Utils.fromJSONString(json);

    ApiBag apiBag = new ApiBag(false);
    List<Api> apis = apiBag.registerObject(new ApiTest());

    ValidatingJsonMap spec = apis.get(0).getSpec();

    assertEquals("POST", spec._getStr("/methods[0]"));
    assertEquals("POST", spec._getStr("/methods[0]"));
    assertEquals("/cluster/package", spec._getStr("/url/paths[0]"));
    assertEquals("string", spec._getStr("/commands/add/properties/package/type"));
    assertEquals("array", spec._getStr("/commands/add/properties/files/type"));
    assertEquals("string", spec._getStr("/commands/add/properties/files/items/type"));
    assertEquals("string", spec._getStr("/commands/delete/items/type"));
    SolrQueryResponse rsp =
        v2ApiInvoke(
            apiBag,
            "/cluster/package",
            "POST",
            new ModifiableSolrParams(),
            new ByteArrayInputStream(
                "{add:{package:mypkg, version: '1.0', files : [a.jar, b.jar]}}".getBytes(UTF_8)));

    AddVersion addversion = (AddVersion) rsp.getValues().get("add");
    assertEquals("mypkg", addversion.pkg);
    assertEquals("1.0", addversion.version);
    assertEquals("a.jar", addversion.files.get(0));
    assertEquals("b.jar", addversion.files.get(1));

    apiBag.registerObject(new C());
    rsp =
        v2ApiInvoke(
            apiBag,
            "/path1",
            "POST",
            new ModifiableSolrParams(),
            new ByteArrayInputStream(
                "{\"package\":\"mypkg\", \"version\": \"1.0\", \"files\" : [\"a.jar\", \"b.jar\"]}"
                    .getBytes(UTF_8)));
    assertEquals("mypkg", rsp.getValues()._getStr("payload/package"));
    assertEquals("1.0", rsp.getValues()._getStr("payload/version"));
  }

  public static class C {
    @EndPoint(path = "/path1", method = POST, permission = PermissionNameProvider.Name.ALL)
    public void m1(PayloadObj<AddVersion> add) {
      add.getResponse().add("payload", add.get());
    }
  }

  @EndPoint(method = POST, path = "/cluster/package", permission = PermissionNameProvider.Name.ALL)
  public static class ApiTest {
    @Command(name = "add")
    public void add(SolrQueryRequest req, SolrQueryResponse rsp, AddVersion addVersion) {
      rsp.add("add", addVersion);
    }

    @Command(name = "delete")
    public void del(SolrQueryRequest req, SolrQueryResponse rsp, List<String> names) {
      rsp.add("delete", names);
    }
  }

  public static class AddVersion implements ReflectMapWriter {
    @JsonProperty(value = "package", required = true)
    public String pkg;

    @JsonProperty(value = "version", required = true)
    public String version;

    @JsonProperty(value = "files", required = true)
    public List<String> files;
  }

  public void testAnnotatedApi() {
    ApiBag apiBag = new ApiBag(false);
    apiBag.registerObject(new DummyTest());
    SolrQueryResponse rsp =
        v2ApiInvoke(
            apiBag,
            "/node/filestore/package/mypkg/jar1.jar",
            "GET",
            new ModifiableSolrParams(),
            null);
    assertEquals("/package/mypkg/jar1.jar", rsp.getValues().get("path"));

    apiBag = new ApiBag(false);
    apiBag.registerObject(new DummyTest1());
    rsp =
        v2ApiInvoke(
            apiBag,
            "/node/filestore/package/mypkg/jar1.jar",
            "GET",
            new ModifiableSolrParams(),
            null);
    assertEquals("/package/mypkg/jar1.jar", rsp.getValues().get("path"));
  }

  @EndPoint(
      path = "/node/filestore/*",
      method = SolrRequest.METHOD.GET,
      permission = PermissionNameProvider.Name.ALL)
  public static class DummyTest {
    @Command
    public void read(SolrQueryRequest req, SolrQueryResponse rsp) {
      rsp.add("FSRead.called", "true");
      rsp.add("path", req.getPathTemplateValues().get("*"));
    }
  }

  public static class DummyTest1 {
    @EndPoint(
        path = "/node/filestore/*",
        method = SolrRequest.METHOD.GET,
        permission = PermissionNameProvider.Name.ALL)
    public void read(SolrQueryRequest req, SolrQueryResponse rsp) {
      rsp.add("FSRead.called", "true");
      rsp.add("path", req.getPathTemplateValues().get("*"));
    }
  }

  private static SolrQueryResponse v2ApiInvoke(
      ApiBag bag, String uri, String method, SolrParams params, InputStream payload) {
    SolrQueryResponse rsp = new SolrQueryResponse();
    HashMap<String, String> templateVals = new HashMap<>();
    Api[] currentApi = new Api[1];

    SolrQueryRequestBase req =
        new SolrQueryRequestBase(null, params) {

          @Override
          public Map<String, String> getPathTemplateValues() {
            return templateVals;
          }

          @Override
          protected Map<String, JsonSchemaValidator> getValidators() {
            return currentApi[0] == null
                ? Collections.emptyMap()
                : currentApi[0].getCommandSchema();
          }

          @Override
          public Iterable<ContentStream> getContentStreams() {
            return Collections.singletonList(
                new ContentStreamBase() {
                  @Override
                  public InputStream getStream() {
                    return payload;
                  }
                });
          }
        };
    Api api = bag.lookup(uri, method, templateVals);
    currentApi[0] = api;

    api.call(req, rsp);
    return rsp;
  }

  public void testTrailingTemplatePaths() {
    PathTrie<Api> registry = new PathTrie<>();
    Api api =
        new Api(EMPTY_SPEC) {
          @Override
          public void call(SolrQueryRequest req, SolrQueryResponse rsp) {}
        };
    Api intropsect = new ApiBag.IntrospectApi(api, false);
    ApiBag.registerIntrospect(
        Collections.emptyMap(), registry, "/c/.system/blob/{name}", intropsect);
    ApiBag.registerIntrospect(
        Collections.emptyMap(), registry, "/c/.system/{x}/{name}", intropsect);
    assertEquals(
        intropsect, registry.lookup("/c/.system/blob/random_string/_introspect", new HashMap<>()));
    assertEquals(intropsect, registry.lookup("/c/.system/blob/_introspect", new HashMap<>()));
    assertEquals(intropsect, registry.lookup("/c/.system/_introspect", new HashMap<>()));
    assertEquals(intropsect, registry.lookup("/c/.system/v1/_introspect", new HashMap<>()));
    assertEquals(intropsect, registry.lookup("/c/.system/v1/v2/_introspect", new HashMap<>()));
  }

  private SolrQueryResponse invoke(
      PluginBag<SolrRequestHandler> reqHandlers,
      String path,
      String fullPath,
      CoreContainer mockCC) {
    HashMap<String, String> parts = new HashMap<>();
    boolean containerHandlerLookup = mockCC.getRequestHandlers() == reqHandlers;
    path = path == null ? fullPath : path;
    Api api = null;
    if (containerHandlerLookup) {
      api = V2HttpCall.getApiInfo(reqHandlers, path, "GET", fullPath, parts);
    } else {
      api = V2HttpCall.getApiInfo(mockCC.getRequestHandlers(), fullPath, "GET", fullPath, parts);
      if (api == null) api = new CompositeApi(null);
      if (api instanceof CompositeApi compositeApi) {
        api = V2HttpCall.getApiInfo(reqHandlers, path, "GET", fullPath, parts);
        compositeApi.add(api);
        api = compositeApi;
      }
    }

    SolrQueryResponse rsp = new SolrQueryResponse();
    LocalSolrQueryRequest req =
        new LocalSolrQueryRequest(null, SolrParams.of()) {
          @Override
          public List<CommandOperation> getCommands(boolean validateInput) {
            return Collections.emptyList();
          }
        };

    api.call(req, rsp);
    return rsp;
  }

  public static void assertConditions(Map<?, ?> root, Map<String, Object> conditions) {
    for (Map.Entry<String, Object> e : conditions.entrySet()) {
      String path = e.getKey();
      List<String> parts = StrUtils.splitSmart(path, path.charAt(0) == '/' ? '/' : ' ', true);
      Object val = Utils.getObjectByPath(root, false, parts);
      if (e.getValue() instanceof ValidatingJsonMap.PredicateWithErrMsg) {
        @SuppressWarnings("unchecked")
        ValidatingJsonMap.PredicateWithErrMsg<Object> value =
            (ValidatingJsonMap.PredicateWithErrMsg<Object>) e.getValue();
        String err = value.test(val);
        if (err != null) {
          assertEquals(
              err + " for " + e.getKey() + " in :" + Utils.toJSONString(root), e.getValue(), val);
        }
      } else {
        assertEquals(
            "incorrect value for path " + e.getKey() + " in :" + Utils.toJSONString(root),
            e.getValue(),
            val);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static CoreContainer getCoreContainerMock(
      final Map<String, Object[]> in, Map<String, Object> out) {
    assumeWorkingMockito();

    CoreContainer mockCC = mock(CoreContainer.class);
    when(mockCC.create(any(String.class), any(Path.class), any(Map.class), anyBoolean()))
        .thenAnswer(
            invocationOnMock -> {
              in.put("create", invocationOnMock.getArguments());
              return null;
            });

    doAnswer(
            invocationOnMock -> {
              in.put("rename", invocationOnMock.getArguments());
              return null;
            })
        .when(mockCC)
        .rename(any(String.class), any(String.class));

    doAnswer(
            invocationOnMock -> {
              in.put("unload", invocationOnMock.getArguments());
              return null;
            })
        .when(mockCC)
        .unload(any(String.class), anyBoolean(), anyBoolean(), anyBoolean());

    when(mockCC.getCoreRootDirectory()).thenReturn(Path.of("coreroot"));
    when(mockCC.getContainerProperties()).thenReturn(new Properties());

    when(mockCC.getRequestHandlers()).thenAnswer(invocationOnMock -> out.get("getRequestHandlers"));
    return mockCC;
  }
}
