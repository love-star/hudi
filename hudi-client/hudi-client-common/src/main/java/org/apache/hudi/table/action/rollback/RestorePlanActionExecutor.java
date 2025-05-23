/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.table.action.rollback;

import org.apache.hudi.avro.model.HoodieInstantInfo;
import org.apache.hudi.avro.model.HoodieRestorePlan;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.ClusteringUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.action.BaseActionExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.common.table.timeline.InstantComparison.GREATER_THAN;

/**
 * Plans the restore action and add a restore.requested meta file to timeline.
 */
public class RestorePlanActionExecutor<T, I, K, O> extends BaseActionExecutor<T, I, K, O, Option<HoodieRestorePlan>> {


  private static final Logger LOG = LoggerFactory.getLogger(RestorePlanActionExecutor.class);

  public static final Integer RESTORE_PLAN_VERSION_1 = 1;
  public static final Integer RESTORE_PLAN_VERSION_2 = 2;
  public static final Integer LATEST_RESTORE_PLAN_VERSION = RESTORE_PLAN_VERSION_2;
  private final String savepointToRestoreTimestamp;

  public RestorePlanActionExecutor(HoodieEngineContext context,
                                   HoodieWriteConfig config,
                                   HoodieTable<T, I, K, O> table,
                                   String instantTime,
                                   String savepointToRestoreTimestamp) {
    super(context, config, table, instantTime);
    this.savepointToRestoreTimestamp = savepointToRestoreTimestamp;
  }

  @Override
  public Option<HoodieRestorePlan> execute() {
    final HoodieInstant restoreInstant = instantGenerator.createNewInstant(HoodieInstant.State.REQUESTED, HoodieTimeline.RESTORE_ACTION, instantTime);
    try {
      // Get all the commits on the timeline after the provided commit time
      // rollback pending clustering instants first before other instants (See HUDI-3362)
      List<HoodieInstant> pendingClusteringInstantsToRollback = table.getActiveTimeline().filterPendingReplaceOrClusteringTimeline()
              // filter only clustering related replacecommits (Not insert_overwrite related commits)
              .filter(instant -> ClusteringUtils.isClusteringInstant(table.getActiveTimeline(), instant, instantGenerator))
              .getReverseOrderedInstants()
              .filter(instant -> GREATER_THAN.test(instant.requestedTime(), savepointToRestoreTimestamp))
              .collect(Collectors.toList());

      // Get all the commits on the timeline after the provided commit time
      List<HoodieInstant> commitInstantsToRollback = table.getActiveTimeline().getWriteTimeline()
              .getReverseOrderedInstants()
              .filter(instant -> GREATER_THAN.test(instant.requestedTime(), savepointToRestoreTimestamp))
              .filter(instant -> !pendingClusteringInstantsToRollback.contains(instant))
              .collect(Collectors.toList());

      // Combine both lists - first rollback pending clustering and then rollback all other commits
      List<HoodieInstantInfo> instantsToRollback = Stream.concat(pendingClusteringInstantsToRollback.stream(), commitInstantsToRollback.stream())
              .map(entry -> new HoodieInstantInfo(entry.requestedTime(), entry.getAction()))
              .collect(Collectors.toList());

      HoodieRestorePlan restorePlan = new HoodieRestorePlan(instantsToRollback, LATEST_RESTORE_PLAN_VERSION, savepointToRestoreTimestamp);
      table.getActiveTimeline().saveToRestoreRequested(restoreInstant, restorePlan);
      table.getMetaClient().reloadActiveTimeline();
      LOG.info("Requesting Restore with instant time " + restoreInstant);
      return Option.of(restorePlan);
    } catch (HoodieIOException e) {
      LOG.error("Got exception when saving restore requested file", e);
      throw e;
    }
  }
}
