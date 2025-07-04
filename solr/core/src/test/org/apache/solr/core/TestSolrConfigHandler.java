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
package org.apache.solr.core;

import static java.util.Arrays.asList;
import static org.apache.solr.common.util.Utils.getObjectByPath;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.LinkedHashMapWriter;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.common.util.Utils;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.handler.DumpRequestHandler;
import org.apache.solr.handler.TestSolrConfigHandlerConcurrent;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrCache;
import org.apache.solr.util.RESTfulServerProvider;
import org.apache.solr.util.RestTestBase;
import org.apache.solr.util.RestTestHarness;
import org.apache.solr.util.TimeOut;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.noggit.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestSolrConfigHandler extends RestTestBase {
  private static final int TIMEOUT_S = 10;

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String collection = "collection1";
  private static final String confDir = collection + "/conf";

  public static ByteBuffer getFileContent(String f) throws IOException {
    return getFileContent(f, true);
  }

  /**
   * @param loadFromClassPath if true, it will look in the classpath to find the file, otherwise
   *     load from absolute filesystem path.
   */
  public static ByteBuffer getFileContent(String f, boolean loadFromClassPath) throws IOException {
    ByteBuffer jar;
    Path file = loadFromClassPath ? getFile(f) : Path.of(f);
    try (InputStream fis = Files.newInputStream(file)) {
      byte[] buf = new byte[fis.available()];
      // TODO: This should check that we read the entire stream
      fis.read(buf);
      jar = ByteBuffer.wrap(buf);
    }
    return jar;
  }

  public static ByteBuffer persistZip(String loc, Class<?>... classes) throws IOException {
    ByteBuffer jar = generateZip(classes);
    try (FileOutputStream fos = new FileOutputStream(loc)) {
      fos.write(jar.array(), jar.arrayOffset(), jar.limit());
      fos.flush();
    }
    return jar;
  }

  public static ByteBuffer generateZip(Class<?>... classes) throws IOException {
    Utils.BAOS bos = new Utils.BAOS();
    try (ZipOutputStream zipOut = new ZipOutputStream(bos)) {
      zipOut.setLevel(ZipOutputStream.DEFLATED);
      for (Class<?> c : classes) {
        String path = c.getName().replace('.', '/').concat(".class");
        ZipEntry entry = new ZipEntry(path);
        ByteBuffer b = Utils.toByteArray(c.getClassLoader().getResourceAsStream(path));
        zipOut.putNextEntry(entry);
        zipOut.write(b.array(), b.arrayOffset(), b.limit());
        zipOut.closeEntry();
      }
    }
    return bos.getByteBuffer();
  }

  @Before
  public void before() throws Exception {
    Path tmpSolrHome = createTempDir();
    PathUtils.copyDirectory(TEST_HOME(), tmpSolrHome);

    final SortedMap<ServletHolder, String> extraServlets = new TreeMap<>();

    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("enable.update.log", "false");

    createJettyAndHarness(
        tmpSolrHome,
        "solrconfig-managed-schema.xml",
        "schema-rest.xml",
        "/solr",
        true,
        extraServlets);
    if (random().nextBoolean()) {
      log.info("These tests are run with V2 API");
      restTestHarness.setServerProvider(
          () -> getBaseUrl() + "/____v2/cores/" + DEFAULT_TEST_CORENAME);
    }
  }

  @After
  public void after() throws Exception {
    solrClientTestRule.reset();

    if (restTestHarness != null) {
      restTestHarness.close();
    }
    restTestHarness = null;
  }

  public void testProperty() throws Exception {
    RestTestHarness harness = restTestHarness;
    MapWriter confMap = getRespMap("/config", harness);
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/luke"), null));
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/system"), null));
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/mbeans"), null));
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/plugins"), null));
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/file"), null));
    assertNotNull(confMap._get(asList("config", "requestHandler", "/admin/ping"), null));

    String payload =
        "{\n"
            + " 'set-property' : { 'updateHandler.autoCommit.maxDocs':100, 'updateHandler.autoCommit.maxTime':10 } \n"
            + " }";
    runConfigCommand(harness, "/config", payload);

    MapWriter m = getRespMap("/config/overlay", harness);
    assertEquals("100", m._getStr("overlay/props/updateHandler/autoCommit/maxDocs"));
    assertEquals("10", m._getStr("overlay/props/updateHandler/autoCommit/maxTime"));

    m = getRespMap("/config/updateHandler", harness);
    assertNotNull(m._get("config/updateHandler/commitWithin/softCommit"));
    assertNotNull(m._get("config/updateHandler/autoCommit/maxDocs"));
    assertNotNull(m._get("config/updateHandler/autoCommit/maxTime"));

    m = getRespMap("/config", harness);
    assertNotNull(m);

    assertEquals("100", m._getStr("config/updateHandler/autoCommit/maxDocs"));
    assertEquals("10", m._getStr("config/updateHandler/autoCommit/maxTime"));

    payload = "{\n" + " 'unset-property' :  'updateHandler.autoCommit.maxDocs' \n" + " }";
    runConfigCommand(harness, "/config", payload);

    m = getRespMap("/config/overlay", harness);
    assertNull(m._get("overlay/props/updateHandler/autoCommit/maxDocs"));
    assertEquals("10", m._getStr("overlay/props/updateHandler/autoCommit/maxTime"));
  }

  public void testUserProp() throws Exception {
    RestTestHarness harness = restTestHarness;
    String payload =
        "{\n"
            + " 'set-user-property' : { 'my.custom.variable.a':'MODIFIEDA',"
            + " 'my.custom.variable.b':'MODIFIEDB' } \n"
            + " }";
    runConfigCommand(harness, "/config", payload);

    MapWriter m = getRespMap("/config/overlay", harness); // .get("overlay");
    assertEquals(m._get("overlay/userProps/my.custom.variable.a"), "MODIFIEDA");
    assertEquals(m._get("overlay/userProps/my.custom.variable.b"), "MODIFIEDB");

    m = getRespMap("/dump?json.nl=map&initArgs=true", harness); // .get("initArgs");

    assertEquals("MODIFIEDA", m._get("initArgs/defaults/a"));
    assertEquals("MODIFIEDB", m._get("initArgs/defaults/b"));
  }

  public void testReqHandlerAPIs() throws Exception {
    reqhandlertests(restTestHarness, null, null);
  }

  public static void runConfigCommand(RestTestHarness harness, String uri, String payload)
      throws IOException {
    String json = SolrTestCaseJ4.json(payload);
    log.info("going to send config command. path {} , payload: {}", uri, payload);
    String response = harness.post(uri, json);
    Map<?, ?> map = (Map<?, ?>) Utils.fromJSONString(response);
    assertNull(response, map.get("errorMessages"));
    assertNull(response, map.get("errors")); // Will this ever be returned?
  }

  public static void runConfigCommandExpectFailure(
      RestTestHarness harness, String uri, String payload, String expectedErrorMessage)
      throws Exception {
    String json = SolrTestCaseJ4.json(payload);
    log.info("going to send config command. path {} , payload: {}", uri, payload);
    String response = harness.post(uri, json);
    Map<?, ?> map = (Map<?, ?>) Utils.fromJSONString(response);
    assertNotNull(response, map.get("errorMessages"));
    assertNotNull(response, map.get("error"));
    assertTrue(
        "Expected status != 0: " + response,
        0L != (Long) ((Map<?, ?>) map.get("responseHeader")).get("status"));
    List<?> errorDetails = (List<?>) ((Map<?, ?>) map.get("error")).get("details");
    List<?> errorMessages = (List<?>) ((Map<?, ?>) errorDetails.get(0)).get("errorMessages");
    assertTrue(
        "Expected '" + expectedErrorMessage + "': " + response,
        errorMessages.get(0).toString().contains(expectedErrorMessage));
  }

  public static void reqhandlertests(
      RestTestHarness writeHarness, String testServerBaseUrl, CloudSolrClient cloudSolrClient)
      throws Exception {
    String payload =
        "{\n"
            + "'create-requesthandler' : { 'name' : '/x', 'class': 'org.apache.solr.handler.DumpRequestHandler' , 'startup' : 'lazy'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);

    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config/overlay",
        cloudSolrClient,
        asList("overlay", "requestHandler", "/x", "startup"),
        "lazy",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'update-requesthandler' : { 'name' : '/x', 'class': 'org.apache.solr.handler.DumpRequestHandler' ,registerPath :'/solr,/v2', "
            + " 'startup' : 'lazy' , 'a':'b' , 'defaults': {'def_a':'def A val', 'multival':['a','b','c']}}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);

    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config/overlay",
        cloudSolrClient,
        asList("overlay", "requestHandler", "/x", "a"),
        "b",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'update-requesthandler' : { 'name' : '/dump', "
            + "'initParams': 'a',"
            + "'class': 'org.apache.solr.handler.DumpRequestHandler' ,"
            + " 'defaults': {'a':'A','b':'B','c':'C'}}\n"
            + "}";

    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config/overlay",
        cloudSolrClient,
        asList("overlay", "requestHandler", "/dump", "defaults", "c"),
        "C",
        TIMEOUT_S);

    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/x?getdefaults=true&json.nl=map",
        cloudSolrClient,
        asList("getdefaults", "def_a"),
        "def A val",
        TIMEOUT_S);

    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/x?param=multival&json.nl=map",
        cloudSolrClient,
        asList("params", "multival"),
        asList("a", "b", "c"),
        TIMEOUT_S);

    payload = "{\n" + "'delete-requesthandler' : '/x'" + "}";
    runConfigCommand(writeHarness, "/config", payload);
    boolean success = false;
    long startTime = System.nanoTime();
    int maxTimeoutSeconds = 10;
    while (TimeUnit.SECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
        < maxTimeoutSeconds) {
      String uri = "/config/overlay";
      Map<?, ?> m =
          testServerBaseUrl == null
              ? getRespMap(uri, writeHarness)
              : TestSolrConfigHandlerConcurrent.getAsMap(testServerBaseUrl + uri, cloudSolrClient);
      if (null == Utils.getObjectByPath(m, true, asList("overlay", "requestHandler", "/x", "a"))) {
        success = true;
        break;
      }
      Thread.sleep(100);
    }
    assertTrue("Could not delete requestHandler  ", success);

    payload =
        "{\n"
            + "'create-queryconverter' : { 'name' : 'qc', 'class': 'org.apache.solr.spelling.SpellingQueryConverter'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "queryConverter", "qc", "class"),
        "org.apache.solr.spelling.SpellingQueryConverter",
        TIMEOUT_S);
    payload =
        "{\n"
            + "'update-queryconverter' : { 'name' : 'qc', 'class': 'org.apache.solr.spelling.SuggestQueryConverter'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "queryConverter", "qc", "class"),
        "org.apache.solr.spelling.SuggestQueryConverter",
        TIMEOUT_S);

    payload = "{\n" + "'delete-queryconverter' : 'qc'" + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "queryConverter", "qc"),
        null,
        TIMEOUT_S);

    payload =
        "{\n"
            + "'add-listener' : { 'event' : 'firstSearcher', 'class': 'solr.QuerySenderListener', "
            + "'name':'f7fb2d87bea44464af2401cf33f42b69', "
            + "'queries':[{'q':'static firstSearcher warming in solrconfig.xml'}]"
            + "}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "listener[0]", "class"),
        "solr.QuerySenderListener",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'update-listener' : { 'event' : 'firstSearcher', 'class': 'org.apache.solr.core.QuerySenderListener', "
            + "'name':'f7fb2d87bea44464af2401cf33f42b69', "
            + "'queries':[{'q':'static firstSearcher warming in solrconfig.xml'}]"
            + "}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "listener[0]", "class"),
        "org.apache.solr.core.QuerySenderListener",
        TIMEOUT_S);

    payload = "{\n" + "'delete-listener' : 'f7fb2d87bea44464af2401cf33f42b69'" + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "listener"),
        null,
        TIMEOUT_S);

    payload =
        "{\n"
            + "'create-searchcomponent' : { 'name' : 'tc', 'class': 'org.apache.solr.handler.component.TermsComponent'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "searchComponent", "tc", "class"),
        "org.apache.solr.handler.component.TermsComponent",
        TIMEOUT_S);
    payload =
        "{\n"
            + "'update-searchcomponent' : { 'name' : 'tc', 'class': 'org.apache.solr.handler.component.TermVectorComponent' }\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "searchComponent", "tc", "class"),
        "org.apache.solr.handler.component.TermVectorComponent",
        TIMEOUT_S);

    payload = "{\n" + "'delete-searchcomponent' : 'tc'" + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "searchComponent", "tc"),
        null,
        TIMEOUT_S);
    // <valueSourceParser name="countUsage"
    // class="org.apache.solr.core.CountUsageValueSourceParser"/>
    payload =
        "{\n"
            + "'create-valuesourceparser' : { 'name' : 'cu', 'class': 'org.apache.solr.core.CountUsageValueSourceParser'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "valueSourceParser", "cu", "class"),
        "org.apache.solr.core.CountUsageValueSourceParser",
        TIMEOUT_S);
    //  <valueSourceParser name="nvl" class="org.apache.solr.search.function.NvlValueSourceParser">
    //    <float name="nvlFloatValue">0.0</float>
    //    </valueSourceParser>
    payload =
        "{\n"
            + "'update-valuesourceparser' : { 'name' : 'cu', 'class': 'org.apache.solr.search.function.NvlValueSourceParser'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "valueSourceParser", "cu", "class"),
        "org.apache.solr.search.function.NvlValueSourceParser",
        TIMEOUT_S);

    payload = "{\n" + "'delete-valuesourceparser' : 'cu'" + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "valueSourceParser", "cu"),
        null,
        TIMEOUT_S);
    //    <transformer name="mytrans2"
    // class="org.apache.solr.response.transform.ValueAugmenterFactory" >
    //    <int name="value">5</int>
    //    </transformer>
    payload =
        "{\n"
            + "'create-transformer' : { 'name' : 'mytrans', 'class': 'org.apache.solr.response.transform.ValueAugmenterFactory', 'value':'5'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "transformer", "mytrans", "class"),
        "org.apache.solr.response.transform.ValueAugmenterFactory",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'update-transformer' : { 'name' : 'mytrans', 'class': 'org.apache.solr.response.transform.ValueAugmenterFactory', 'value':'6'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config",
        cloudSolrClient,
        asList("config", "transformer", "mytrans", "value"),
        "6",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'delete-transformer' : 'mytrans',"
            + "'create-initparams' : { 'name' : 'hello', 'key':'val'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    Map<?, ?> map =
        testForResponseElement(
            writeHarness,
            testServerBaseUrl,
            "/config",
            cloudSolrClient,
            asList("config", "transformer", "mytrans"),
            null,
            TIMEOUT_S);

    List<?> l = (List<?>) Utils.getObjectByPath(map, false, asList("config", "initParams"));
    assertNotNull("no object /config/initParams : " + map, l);
    assertEquals(2, l.size());
    assertEquals("val", ((Map) l.get(1)).get("key"));

    payload =
        "{\n"
            + "    'add-searchcomponent': {\n"
            + "        'name': 'myspellcheck',\n"
            + "        'class': 'solr.SpellCheckComponent',\n"
            + "        'queryAnalyzerFieldType': 'text_general',\n"
            + "        'spellchecker': {\n"
            + "            'name': 'default',\n"
            + "            'field': '_text_',\n"
            + "            'class': 'solr.DirectSolrSpellChecker'\n"
            + "        }\n"
            + "    }\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    map =
        testForResponseElement(
            writeHarness,
            testServerBaseUrl,
            "/config",
            cloudSolrClient,
            asList("config", "searchComponent", "myspellcheck", "spellchecker", "class"),
            "solr.DirectSolrSpellChecker",
            TIMEOUT_S);

    payload =
        "{\n"
            + "    'add-requesthandler': {\n"
            + "        name : '/dump100',\n"
            + "       registerPath :'/solr,/v2',"
            + "        class : 'org.apache.solr.handler.DumpRequestHandler',"
            + "        suggester: [{name: s1,lookupImpl: FuzzyLookupFactory, dictionaryImpl : DocumentDictionaryFactory},"
            + "                    {name: s2,lookupImpl: FuzzyLookupFactory , dictionaryImpl : DocumentExpressionDictionaryFactory}]"
            + "    }\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);
    map =
        testForResponseElement(
            writeHarness,
            testServerBaseUrl,
            "/config",
            cloudSolrClient,
            asList("config", "requestHandler", "/dump100", "class"),
            "org.apache.solr.handler.DumpRequestHandler",
            TIMEOUT_S);

    map = getRespMap("/dump100?json.nl=arrmap&initArgs=true", writeHarness);
    List<?> initArgs = (List<?>) map.get("initArgs");
    assertNotNull(initArgs);
    assertTrue(initArgs.size() >= 2);
    assertTrue(((Map) initArgs.get(2)).containsKey("suggester"));
    assertTrue(((Map) initArgs.get(1)).containsKey("suggester"));

    payload =
        "{\n"
            + "'add-requesthandler' : { 'name' : '/dump101', 'class': "
            + "'"
            + CacheTest.class.getName()
            + "', "
            + "    registerPath :'/solr,/v2'"
            + ", 'startup' : 'lazy'}\n"
            + "}";
    runConfigCommand(writeHarness, "/config", payload);

    testForResponseElement(
        writeHarness,
        testServerBaseUrl,
        "/config/overlay",
        cloudSolrClient,
        asList("overlay", "requestHandler", "/dump101", "startup"),
        "lazy",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'add-cache' : {name:'lfuCacheDecayFalse', class:'solr.search.CaffeineCache', size:10 ,initialSize:9 , timeDecay:false },"
            + "'add-cache' : {name: 'perSegFilter', class: 'solr.search.CaffeineCache', size:10, initialSize:0 , autowarmCount:10}}";
    runConfigCommand(writeHarness, "/config", payload);

    map =
        testForResponseElement(
            writeHarness,
            testServerBaseUrl,
            "/config/overlay",
            cloudSolrClient,
            asList("overlay", "cache", "lfuCacheDecayFalse", "class"),
            "solr.search.CaffeineCache",
            TIMEOUT_S);
    assertEquals(
        "solr.search.CaffeineCache",
        getObjectByPath(map, true, List.of("overlay", "cache", "perSegFilter", "class")));

    map =
        getRespMap("/dump101?cacheNames=lfuCacheDecayFalse&cacheNames=perSegFilter", writeHarness);
    assertEquals(
        "Actual output " + Utils.toJSONString(map),
        "org.apache.solr.search.CaffeineCache",
        getObjectByPath(map, true, List.of("caches", "perSegFilter")));
    assertEquals(
        "Actual output " + Utils.toJSONString(map),
        "org.apache.solr.search.CaffeineCache",
        getObjectByPath(map, true, List.of("caches", "lfuCacheDecayFalse")));
  }

  public void testFailures() throws Exception {
    String payload = "{ not-a-real-command: { param1: value1, param2: value2 } }";
    runConfigCommandExpectFailure(
        restTestHarness, "/config", payload, "Unknown operation 'not-a-real-command'");

    payload = "{ set-property: { update.autoCreateFields: false } }";
    runConfigCommandExpectFailure(
        restTestHarness,
        "/config",
        payload,
        "'update.autoCreateFields' is not an editable property");

    payload = "{ set-property: { updateHandler.autoCommit.maxDocs: false } }";
    runConfigCommandExpectFailure(
        restTestHarness,
        "/config",
        payload,
        "Property updateHandler.autoCommit.maxDocs must be of Integer type");

    payload = "{ unset-property: not-an-editable-property }";
    runConfigCommandExpectFailure(
        restTestHarness,
        "/config",
        payload,
        "'[not-an-editable-property]' is not an editable property");

    for (String component :
        new String[] {
          "requesthandler", "searchcomponent", "initparams", "queryresponsewriter", "queryparser",
          "valuesourceparser", "transformer", "updateprocessor", "queryconverter", "listener"
        }) {
      for (String operation : new String[] {"add", "update"}) {
        payload = "{ " + operation + "-" + component + ": { param1: value1 } }";
        runConfigCommandExpectFailure(
            restTestHarness, "/config", payload, "'name' is a required field");
      }
      payload = "{ delete-" + component + ": not-a-real-component-name }";
      runConfigCommandExpectFailure(restTestHarness, "/config", payload, "NO such ");
    }
  }

  public static class CacheTest extends DumpRequestHandler {
    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
      super.handleRequestBody(req, rsp);
      String[] caches = req.getParams().getParams("cacheNames");
      if (caches != null && caches.length > 0) {
        HashMap<String, String> m = new HashMap<>();
        rsp.add("caches", m);
        for (String c : caches) {
          SolrCache<?, ?> cache = req.getSearcher().getCache(c);
          if (cache != null) m.put(c, cache.getClass().getName());
        }
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static LinkedHashMapWriter testForResponseElement(
      RestTestHarness harness,
      String testServerBaseUrl,
      String uri,
      CloudSolrClient cloudSolrClient,
      List<String> jsonPath,
      Object expected,
      long maxTimeoutSeconds)
      throws Exception {

    boolean success = false;
    LinkedHashMapWriter m = null;

    TimeOut timeOut = new TimeOut(maxTimeoutSeconds, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    while (!timeOut.hasTimedOut()) {
      try {
        m =
            testServerBaseUrl == null
                ? getRespMap(uri, harness)
                : TestSolrConfigHandlerConcurrent.getAsMap(
                    testServerBaseUrl + uri, cloudSolrClient);
      } catch (Exception e) {
        Thread.sleep(100);
        continue;
      }
      Object actual = Utils.getObjectByPath(m, false, jsonPath);

      if (expected instanceof ValidatingJsonMap.PredicateWithErrMsg predicate) {
        if (predicate.test(actual) == null) {
          success = true;
          break;
        }

      } else {
        if (Objects.equals(expected, actual)) {
          success = true;
          break;
        }
      }
      Thread.sleep(100);
    }
    assertTrue(
        StrUtils.formatString(
            "Could not get expected value  ''{0}'' for path ''{1}'' full output: {2},  from server:  {3}",
            expected, StrUtils.join(jsonPath, '/'), m.toString(), testServerBaseUrl),
        success);

    return m;
  }

  public void testReqParams() throws Exception {
    RestTestHarness harness = restTestHarness;
    String payload =
        " {\n"
            + "  'set' : {'x': {"
            + "                    'a':'A val',\n"
            + "                    'b': 'B val'}\n"
            + "             }\n"
            + "  }";

    TestSolrConfigHandler.runConfigCommand(harness, "/config/params", payload);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "x", "a"),
        "A val",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "x", "b"),
        "B val",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'create-requesthandler' : { 'name' : '/d', registerPath :'/solr,/v2' , 'class': 'org.apache.solr.handler.DumpRequestHandler' }\n"
            + "}";

    TestSolrConfigHandler.runConfigCommand(harness, "/config", payload);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/overlay",
        null,
        asList("overlay", "requestHandler", "/d", "name"),
        "/d",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness, null, "/d?useParams=x", null, asList("params", "a"), "A val", TIMEOUT_S);
    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/d?useParams=x&a=fomrequest",
        null,
        asList("params", "a"),
        "fomrequest",
        TIMEOUT_S);

    payload =
        "{\n"
            + "'create-requesthandler' : { 'name' : '/dump1', registerPath :'/solr,/v2' , 'class': 'org.apache.solr.handler.DumpRequestHandler', 'useParams':'x' }\n"
            + "}";

    TestSolrConfigHandler.runConfigCommand(harness, "/config", payload);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/overlay",
        null,
        asList("overlay", "requestHandler", "/dump1", "name"),
        "/dump1",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness, null, "/dump1", null, asList("params", "a"), "A val", TIMEOUT_S);

    payload =
        " {\n"
            + "  'set' : {'y':{\n"
            + "                'c':'CY val',\n"
            + "                'b': 'BY val', "
            + "                'd': ['val 1', 'val 2']}\n"
            + "             }\n"
            + "  }";

    TestSolrConfigHandler.runConfigCommand(harness, "/config/params", payload);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "c"),
        "CY val",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness, null, "/dump1?useParams=y", null, asList("params", "c"), "CY val", TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness, null, "/dump1?useParams=y", null, asList("params", "b"), "BY val", TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness, null, "/dump1?useParams=y", null, asList("params", "a"), "A val", TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/dump1?useParams=y",
        null,
        asList("params", "d"),
        asList("val 1", "val 2"),
        TIMEOUT_S);

    payload =
        " {\n"
            + "  'update' : {'y': {\n"
            + "                'c':'CY val modified',\n"
            + "                'e':'EY val',\n"
            + "                'b': 'BY val'"
            + "}\n"
            + "             }\n"
            + "  }";

    TestSolrConfigHandler.runConfigCommand(harness, "/config/params", payload);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "c"),
        "CY val modified",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "e"),
        "EY val",
        TIMEOUT_S);

    payload =
        " {\n"
            + "  'set' : {'y': {\n"
            + "                'p':'P val',\n"
            + "                'q': 'Q val'"
            + "}\n"
            + "             }\n"
            + "  }";

    TestSolrConfigHandler.runConfigCommand(harness, "/config/params", payload);
    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "p"),
        "P val",
        TIMEOUT_S);

    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "c"),
        null,
        TIMEOUT_S);
    payload = " {'delete' : 'y'}";
    TestSolrConfigHandler.runConfigCommand(harness, "/config/params", payload);
    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/params",
        null,
        asList("response", "params", "y", "p"),
        null,
        TIMEOUT_S);

    payload =
        "{\n"
            + "  'create-requesthandler': {\n"
            + "    'name': 'aRequestHandler',\n"
            + "    'registerPath': '/v2',\n"
            + "    'class': 'org.apache.solr.handler.DumpRequestHandler',\n"
            + "    'spec': {\n"
            + "      'methods': [\n"
            + "        'GET',\n"
            + "        'POST'\n"
            + "      ],\n"
            + "      'url': {\n"
            + "        'paths': [\n"
            + "          '/something/{part1}/fixed/{part2}'\n"
            + "        ]\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}";

    TestSolrConfigHandler.runConfigCommand(harness, "/config", payload);
    TestSolrConfigHandler.testForResponseElement(
        harness,
        null,
        "/config/overlay",
        null,
        asList("overlay", "requestHandler", "aRequestHandler", "class"),
        "org.apache.solr.handler.DumpRequestHandler",
        TIMEOUT_S);
    RESTfulServerProvider oldProvider = restTestHarness.getServerProvider();
    restTestHarness.setServerProvider(
        () -> getBaseUrl() + "/____v2/cores/" + DEFAULT_TEST_CORENAME);

    Map<?, ?> rsp =
        TestSolrConfigHandler.testForResponseElement(
            harness,
            null,
            "/something/part1_Value/fixed/part2_Value?urlTemplateValues=part1&urlTemplateValues=part2",
            null,
            asList("urlTemplateValues"),
            new ValidatingJsonMap.PredicateWithErrMsg<>() {
              @Override
              public String test(Object o) {
                if (o instanceof Map<?, ?> m) {
                  if ("part1_Value".equals(m.get("part1")) && "part2_Value".equals(m.get("part2")))
                    return null;
                }
                return "no match";
              }

              @Override
              public String toString() {
                return "{part1:part1_Value, part2 : part2_Value]";
              }
            },
            TIMEOUT_S);
    restTestHarness.setServerProvider(oldProvider);
  }

  @SuppressWarnings({"rawtypes"})
  public static LinkedHashMapWriter getRespMap(String path, RestTestHarness restHarness)
      throws Exception {
    String response = restHarness.query(path);
    try {
      return (LinkedHashMapWriter)
          Utils.MAPWRITEROBJBUILDER.apply(Utils.getJSONParser(new StringReader(response))).getVal();
    } catch (JSONParser.ParseException e) {
      log.error(response);
      return new LinkedHashMapWriter<>();
    }
  }

  public void testCacheDisableSolrConfig() throws Exception {
    RESTfulServerProvider oldProvider = restTestHarness.getServerProvider();
    restTestHarness.setServerProvider(() -> getBaseUrl());
    MapWriter confMap = getRespMap("/admin/metrics", restTestHarness);
    assertNotNull(
        confMap._get(
            asList("metrics", "solr.core.collection1", "CACHE.searcher.fieldValueCache"), null));
    // Here documentCache is disabled at initialization in SolrConfig
    assertNull(
        confMap._get(
            asList("metrics", "solr.core.collection1", "CACHE.searcher.documentCache"), null));
    restTestHarness.setServerProvider(oldProvider);
  }

  public void testSetPropertyCacheSize() throws Exception {
    RESTfulServerProvider oldProvider = restTestHarness.getServerProvider();
    // Changing cache size
    String payload = "{'set-property' : { 'query.documentCache.size': 399} }";
    runConfigCommand(restTestHarness, "/config", payload);
    MapWriter overlay = getRespMap("/config/overlay", restTestHarness);
    assertEquals("399", overlay._getStr("overlay/props/query/documentCache/size"));
    // Setting size only will not enable the cache
    restTestHarness.setServerProvider(() -> getBaseUrl());
    MapWriter confMap = getRespMap("/admin/metrics", restTestHarness);
    assertNull(
        confMap._get(
            asList("metrics", "solr.core.collection1", "CACHE.searcher.documentCache"), null));
    restTestHarness.setServerProvider(oldProvider);
  }

  public void testSetPropertyEnableAndDisableCache() throws Exception {
    RESTfulServerProvider oldProvider = restTestHarness.getServerProvider();
    // Enabling Cache
    String payload = "{'set-property' : { 'query.documentCache.enabled': true} }";
    runConfigCommand(restTestHarness, "/config", payload);
    restTestHarness.setServerProvider(() -> getBaseUrl());
    MapWriter confMap = getRespMap("/admin/metrics", restTestHarness);
    assertNotNull(
        confMap._get(
            asList("metrics", "solr.core.collection1", "CACHE.searcher.documentCache"), null));

    // Disabling Cache
    payload = "{ 'set-property' : { 'query.documentCache.enabled': false } }";
    restTestHarness.setServerProvider(oldProvider);
    runConfigCommand(restTestHarness, "/config", payload);
    restTestHarness.setServerProvider(() -> getBaseUrl());
    confMap = getRespMap("/admin/metrics", restTestHarness);
    assertNull(
        confMap._get(
            asList("metrics", "solr.core.collection1", "CACHE.searcher.documentCache"), null));

    restTestHarness.setServerProvider(oldProvider);
  }
}
