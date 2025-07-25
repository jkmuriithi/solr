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
package org.apache.solr.s3;

import com.adobe.testing.s3mock.junit4.S3MockRule;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.cloud.SocketProxy;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;

/** Abstract class for test with S3Mock. */
public class AbstractS3ClientTest extends SolrTestCaseJ4 {

  protected static final String BUCKET_NAME = "test-bucket";

  @ClassRule
  @SuppressWarnings("removal")
  public static final S3MockRule S3_MOCK_RULE =
      S3MockRule.builder().withInitialBuckets(BUCKET_NAME).build();

  S3StorageClient client;
  private SocketProxy proxy;

  @Before
  public void setUpClient() throws Exception {
    System.setProperty("aws.accessKeyId", "foo");
    System.setProperty("aws.secretAccessKey", "bar");

    setS3ConfFile();

    // We are using a proxy in front of S3Mock to be able to test connection loss
    proxy = new SocketProxy();
    proxy.open(URI.create("http://localhost:" + S3_MOCK_RULE.getHttpPort()));
    client =
        new S3StorageClient(
            BUCKET_NAME,
            null,
            "us-east-1",
            "",
            false,
            "http://localhost:" + proxy.getListenPort(),
            false);
  }

  /**
   * Use this to make sure that we don't pollute the test environment with defaults from the local
   * user's ~/.aws/config or credentials
   */
  public static void setS3ConfFile() throws URISyntaxException {
    URI conf = S3IncrementalBackupTest.class.getClassLoader().getResource("s3.conf").toURI();
    String emptyFile = Path.of(conf).toString();
    System.setProperty(ProfileFileSystemSetting.AWS_CONFIG_FILE.property(), emptyFile);
    System.setProperty(ProfileFileSystemSetting.AWS_SHARED_CREDENTIALS_FILE.property(), emptyFile);
  }

  @After
  public void tearDownClient() {
    client.close();
    proxy.close();
  }

  /**
   * Helper method to push a string to S3.
   *
   * @param path Destination path in S3.
   * @param content Arbitrary content for the test.
   */
  void pushContent(String path, String content) throws S3Exception {
    pushContent(path, content.getBytes(StandardCharsets.UTF_8));
  }

  void pushContent(String path, byte[] content) throws S3Exception {
    try (OutputStream output = client.pushStream(path)) {
      output.write(content);
    } catch (IOException e) {
      throw new S3Exception(e);
    }
  }

  /**
   * Test a connection loss in S3. This will close the existing connections receiving socket, while
   * keeping S3 open to new connections. This affects all connections open to S3 at the time of
   * calling.
   */
  void initiateS3ConnectionLoss() {
    proxy.halfClose();
  }
}
