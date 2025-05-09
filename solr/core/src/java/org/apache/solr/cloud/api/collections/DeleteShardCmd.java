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
package org.apache.solr.cloud.api.collections;

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.NODE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.SHARD_ID_PROP;
import static org.apache.solr.common.params.CollectionAdminParams.FOLLOW_ALIASES;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.DELETESHARD;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.solr.cloud.DistributedClusterStateUpdater;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteShardCmd implements CollApiCmds.CollectionApiCommand {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final CollectionCommandContext ccc;

  public DeleteShardCmd(CollectionCommandContext ccc) {
    this.ccc = ccc;
  }

  @Override
  public void call(ClusterState clusterState, ZkNodeProps message, NamedList<Object> results)
      throws Exception {
    String extCollectionName = message.getStr(ZkStateReader.COLLECTION_PROP);
    String sliceId = message.getStr(ZkStateReader.SHARD_ID_PROP);

    boolean followAliases = message.getBool(FOLLOW_ALIASES, false);
    String collectionName;
    if (followAliases) {
      collectionName =
          ccc.getSolrCloudManager().getClusterStateProvider().resolveSimpleAlias(extCollectionName);
    } else {
      collectionName = extCollectionName;
    }

    log.info("Delete shard invoked");
    Slice slice = clusterState.getCollection(collectionName).getSlice(sliceId);
    if (slice == null)
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "No shard with name " + sliceId + " exists for collection " + collectionName);

    // For now, only allow for deletions of Inactive slices or custom hashes (range==null).
    // TODO: Add check for range gaps on Slice deletion
    final Slice.State state = slice.getState();
    if (!(slice.getRange() == null
            || state == Slice.State.INACTIVE
            || state == Slice.State.RECOVERY
            || state == Slice.State.CONSTRUCTION)
        || state == Slice.State.RECOVERY_FAILED) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "The slice: "
              + slice.getName()
              + " is currently "
              + state
              + ". Only non-active (or custom-hashed) slices can be deleted.");
    }

    if (state == Slice.State.RECOVERY) {
      // mark the slice as 'construction' and only then try to delete the cores
      // see SOLR-9455
      Map<String, Object> propMap = new HashMap<>();
      propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
      propMap.put(sliceId, Slice.State.CONSTRUCTION.toString());
      propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
      ZkNodeProps m = new ZkNodeProps(propMap);
      if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
        // In this DeleteShardCmd.call() method there are potentially two cluster state updates.
        // This is the first one. Even though the code of this method does not wait for it to
        // complete, it does call the Collection API before it issues the second state change below.
        // The collection API will be doing its own state change(s), and these will happen after
        // this one (given it's for the same collection). Therefore we persist this state change
        // immediately and do not group it with the one done further down. Once the Collection API
        // is also distributed (and not only the cluster state updates), we will likely be able to
        // batch more/all cluster state updates done by this command (DeleteShardCmd). TODO
        // SOLR-15146
        ccc.getDistributedClusterStateUpdater()
            .doSingleStateUpdate(
                DistributedClusterStateUpdater.MutatingCommand.SliceUpdateShardState,
                m,
                ccc.getSolrCloudManager(),
                ccc.getZkStateReader());
      } else {
        ccc.offerStateUpdate(m);
      }
    }

    String asyncId = message.getStr(ASYNC);

    try {
      List<ZkNodeProps> replicas = getReplicasForSlice(collectionName, slice);
      CountDownLatch cleanupLatch = new CountDownLatch(replicas.size());
      for (ZkNodeProps r : replicas) {
        final ZkNodeProps replica =
            r.plus(message.getProperties()).plus("parallel", "true").plus(ASYNC, asyncId);
        if (log.isInfoEnabled()) {
          log.info(
              "Deleting replica for collection={} shard={} on node={}",
              replica.getStr(COLLECTION_PROP),
              replica.getStr(SHARD_ID_PROP),
              replica.getStr(CoreAdminParams.NODE));
        }
        NamedList<Object> deleteResult = new NamedList<>();
        try {
          new DeleteReplicaCmd(ccc)
              .deleteReplica(
                  clusterState,
                  replica,
                  deleteResult,
                  () -> {
                    cleanupLatch.countDown();
                    if (deleteResult.get("failure") != null) {
                      synchronized (results) {
                        results.add(
                            "failure",
                            String.format(
                                Locale.ROOT,
                                "Failed to delete replica for collection=%s shard=%s"
                                    + " on node=%s",
                                replica.getStr(COLLECTION_PROP),
                                replica.getStr(SHARD_ID_PROP),
                                replica.getStr(NODE_NAME_PROP)));
                      }
                    }
                    @SuppressWarnings("unchecked")
                    SimpleOrderedMap<Object> success =
                        (SimpleOrderedMap<Object>) deleteResult.get("success");
                    if (success != null) {
                      synchronized (results) {
                        results.add("success", success);
                      }
                    }
                  });
        } catch (KeeperException e) {
          log.warn("Error deleting replica: {}", r, e);
          cleanupLatch.countDown();
        } catch (Exception e) {
          log.warn("Error deleting replica: {}", r, e);
          cleanupLatch.countDown();
          throw e;
        }
      }
      log.debug("Waiting for delete shard action to complete");
      cleanupLatch.await(1, TimeUnit.MINUTES);

      ZkNodeProps m =
          new ZkNodeProps(
              Overseer.QUEUE_OPERATION,
              DELETESHARD.toLower(),
              ZkStateReader.COLLECTION_PROP,
              collectionName,
              ZkStateReader.SHARD_ID_PROP,
              sliceId);
      ZkStateReader zkStateReader = ccc.getZkStateReader();
      if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
        ccc.getDistributedClusterStateUpdater()
            .doSingleStateUpdate(
                DistributedClusterStateUpdater.MutatingCommand.CollectionDeleteShard,
                m,
                ccc.getSolrCloudManager(),
                ccc.getZkStateReader());
      } else {
        ccc.offerStateUpdate(m);
      }
      cleanupZooKeeperShardMetadata(collectionName, sliceId);
      zkStateReader.waitForState(
          collectionName, 45, TimeUnit.SECONDS, (c) -> c.getSlice(sliceId) == null);

      log.info("Successfully deleted collection: {} , shard: {}", collectionName, sliceId);
    } catch (SolrException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Error executing delete operation for collection: "
              + collectionName
              + " shard: "
              + sliceId,
          e);
    }
  }

  private List<ZkNodeProps> getReplicasForSlice(String collectionName, Slice slice) {
    List<ZkNodeProps> sourceReplicas = new ArrayList<>();
    for (Replica replica : slice.getReplicas()) {
      ZkNodeProps props =
          new ZkNodeProps(
              COLLECTION_PROP,
              collectionName,
              SHARD_ID_PROP,
              slice.getName(),
              ZkStateReader.CORE_NAME_PROP,
              replica.getCoreName(),
              ZkStateReader.REPLICA_PROP,
              replica.getName(),
              CoreAdminParams.NODE,
              replica.getNodeName());
      sourceReplicas.add(props);
    }
    return sourceReplicas;
  }

  /**
   * Best effort to delete Zookeeper nodes that stored other details than the shard itself in
   * cluster state. If we fail for any reason, we just log and the shard is still deleted.
   */
  private void cleanupZooKeeperShardMetadata(String collection, String sliceId)
      throws InterruptedException {

    String[] cleanupPaths =
        new String[] {
          ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/leader_elect/" + sliceId,
          ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/leaders/" + sliceId,
          ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/terms/" + sliceId
        };

    SolrZkClient client = ccc.getZkStateReader().getZkClient();
    for (String path : cleanupPaths) {
      try {
        client.clean(path);
      } catch (KeeperException ex) {
        log.warn(
            "Non-fatal error occurred when deleting shard metadata {}/{} at path {}",
            collection,
            sliceId,
            path,
            ex);
      }
    }
  }
}
