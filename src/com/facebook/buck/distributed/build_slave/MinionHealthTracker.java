/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks the health of Minions to make sure they don't silently die and the build hangs. */
public class MinionHealthTracker {
  private static final Logger LOG = Logger.get(MinionHealthTracker.class);

  private final Map<String, MinionTrackingInfo> minions;
  private final Set<String> untrackedMinions;
  private final Clock clock;
  private final long maxMinionSilenceMillis;

  public MinionHealthTracker(Clock clock, long maxMinionSilenceMillis) {
    Preconditions.checkArgument(
        maxMinionSilenceMillis > 0,
        "The maxMinionSilenceMillis value needs to be positive. Found [%d] instead.",
        maxMinionSilenceMillis);
    this.maxMinionSilenceMillis = maxMinionSilenceMillis;
    this.minions = Maps.newConcurrentMap();
    this.untrackedMinions = Sets.newConcurrentHashSet();
    this.clock = clock;
  }

  /** Heartbeat reports a minion is currently alive and happily running. */
  public void reportMinionAlive(String minionId) {
    LOG.debug(String.format("Received keep alive heartbeat from Minion [%s]", minionId));
    minions
        .computeIfAbsent(minionId, key -> new MinionTrackingInfo(minionId, clock))
        .reportHealthy();
  }

  /** Returns all minions that are currently thought to be dead/not-healthy. */
  public List<String> getDeadMinions() {
    List<String> deadMinionIds = Lists.newArrayList();
    long currentMillis = clock.currentTimeMillis();
    for (MinionTrackingInfo minion : minions.values()) {
      if (untrackedMinions.contains(minion.getMinionId())) {
        continue;
      }

      long lastHealthCheckMillis = minion.getLastHealthCheckMillis();
      if ((currentMillis - lastHealthCheckMillis) > maxMinionSilenceMillis) {
        LOG.error(
            String.format(
                "Minion [%s] failed healthcheck. Last heartbeat ts [%d]. Current ts [%d].",
                minion.minionId, lastHealthCheckMillis, currentMillis));
        deadMinionIds.add(minion.getMinionId());
      }
    }
    return deadMinionIds;
  }

  /**
   * Tell the tracker it is fine to forever stop tracking this minion. This class is thread-safe.
   */
  public void stopTrackingForever(String minionId) {
    untrackedMinions.add(minionId);
  }

  private static class MinionTrackingInfo {

    private final String minionId;
    private final Clock clock;
    private long lastHealthCheckMillis;

    public MinionTrackingInfo(String minionId, Clock clock) {
      this.minionId = minionId;
      this.clock = clock;
      reportHealthy();
    }

    public synchronized void reportHealthy() {
      lastHealthCheckMillis = clock.currentTimeMillis();
      LOG.debug(
          String.format(
              "Updated keep alive ts for Minion [%s] to [%d]", minionId, lastHealthCheckMillis));
    }

    public synchronized long getLastHealthCheckMillis() {
      return lastHealthCheckMillis;
    }

    public String getMinionId() {
      return minionId;
    }
  }
}
