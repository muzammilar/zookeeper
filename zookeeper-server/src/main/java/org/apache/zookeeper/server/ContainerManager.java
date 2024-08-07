/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.zookeeper.DeleteContainerRequest;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages cleanup of container ZNodes. This class is meant to only
 * be run from the leader. There's no harm in running from followers/observers
 * but that will be extra work that's not needed. Once started, it periodically
 * checks container nodes that have a cversion &gt; 0 and have no children. A
 * delete is attempted on the node. The result of the delete is unimportant.
 * If the proposal fails or the container node is not empty there's no harm.
 */
public class ContainerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerManager.class);
    private final ZKDatabase zkDb;
    private final RequestProcessor requestProcessor;
    private final int checkIntervalMs;
    private final int maxPerMinute;
    private final long maxNeverUsedIntervalMs;
    private final Timer timer;
    private final AtomicReference<TimerTask> task = new AtomicReference<>(null);

    /**
     * @param zkDb the ZK database
     * @param requestProcessor request processor - used to inject delete
     *                         container requests
     * @param checkIntervalMs how often to check containers in milliseconds
     * @param maxPerMinute the max containers to delete per second - avoids
     *                     herding of container deletions
     */
    public ContainerManager(ZKDatabase zkDb, RequestProcessor requestProcessor, int checkIntervalMs, int maxPerMinute) {
        this(zkDb, requestProcessor, checkIntervalMs, maxPerMinute, 0);
    }

    /**
     * @param zkDb the ZK database
     * @param requestProcessor request processor - used to inject delete
     *                         container requests
     * @param checkIntervalMs how often to check containers in milliseconds
     * @param maxPerMinute the max containers to delete per second - avoids
     *                     herding of container deletions
     * @param maxNeverUsedIntervalMs the max time in milliseconds that a container that has never had
     *                                  any children is retained
     */
    public ContainerManager(ZKDatabase zkDb, RequestProcessor requestProcessor, int checkIntervalMs, int maxPerMinute, long maxNeverUsedIntervalMs) {
        this.zkDb = zkDb;
        this.requestProcessor = requestProcessor;
        this.checkIntervalMs = checkIntervalMs;
        this.maxPerMinute = maxPerMinute;
        this.maxNeverUsedIntervalMs = maxNeverUsedIntervalMs;
        timer = new Timer("ContainerManagerTask", true);

        LOG.info("Using checkIntervalMs={} maxPerMinute={} maxNeverUsedIntervalMs={}", checkIntervalMs, maxPerMinute, maxNeverUsedIntervalMs);
    }

    /**
     * start/restart the timer the runs the check. Can safely be called
     * multiple times.
     */
    public void start() {
        if (task.get() == null) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    try {
                        checkContainers();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.info("interrupted");
                        cancel();
                    } catch (Throwable e) {
                        LOG.error("Error checking containers", e);
                    }
                }
            };
            if (task.compareAndSet(null, timerTask)) {
                timer.scheduleAtFixedRate(timerTask, checkIntervalMs, checkIntervalMs);
            }
        }
    }

    /**
     * stop the timer if necessary. Can safely be called multiple times.
     */
    public void stop() {
        TimerTask timerTask = task.getAndSet(null);
        if (timerTask != null) {
            timerTask.cancel();
        }
        timer.cancel();
    }

    /**
     * Manually check the containers. Not normally used directly
     */
    public void checkContainers() throws InterruptedException {
        long minIntervalMs = getMinIntervalMs();
        for (String containerPath : getCandidates()) {
            long startMs = Time.currentElapsedTime();

            DeleteContainerRequest record = new DeleteContainerRequest(containerPath);
            Request request = new Request(null, 0, 0, ZooDefs.OpCode.deleteContainer, RequestRecord.fromRecord(record), null);
            try {
                LOG.info("Attempting to delete candidate container: {}", containerPath);
                postDeleteRequest(request);
            } catch (Exception e) {
                LOG.error("Could not delete container: {}", containerPath, e);
            }

            long elapsedMs = Time.currentElapsedTime() - startMs;
            long waitMs = minIntervalMs - elapsedMs;
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
        }
    }

    // VisibleForTesting
    protected void postDeleteRequest(Request request) throws RequestProcessor.RequestProcessorException {
        requestProcessor.processRequest(request);
    }

    // VisibleForTesting
    protected long getMinIntervalMs() {
        return TimeUnit.MINUTES.toMillis(1) / maxPerMinute;
    }

    // VisibleForTesting
    protected Collection<String> getCandidates() {
        Set<String> candidates = new HashSet<>();
        for (String containerPath : zkDb.getDataTree().getContainers()) {
            DataNode node = zkDb.getDataTree().getNode(containerPath);
            if ((node != null) && node.getChildren().isEmpty()) {
                /*
                    cversion > 0: keep newly created containers from being deleted
                    before any children have been added. If you were to create the
                    container just before a container cleaning period the container
                    would be immediately be deleted.
                 */
                if (node.stat.getCversion() > 0) {
                    candidates.add(containerPath);
                } else {
                    /*
                        Users may not want unused containers to live indefinitely. Allow a system
                        property to be set that sets the max time for a cversion-0 container
                        to stay before being deleted
                     */
                    if ((maxNeverUsedIntervalMs != 0) && (getElapsed(node) > maxNeverUsedIntervalMs)) {
                        candidates.add(containerPath);
                    }
                }
            }
        }
        for (String ttlPath : zkDb.getDataTree().getTtls()) {
            DataNode node = zkDb.getDataTree().getNode(ttlPath);
            if (node != null) {
                Set<String> children = node.getChildren();
                if (children.isEmpty()) {
                    if (EphemeralType.get(node.stat.getEphemeralOwner()) == EphemeralType.TTL) {
                        long ttl = EphemeralType.TTL.getValue(node.stat.getEphemeralOwner());
                        if ((ttl != 0) && (getElapsed(node) > ttl)) {
                            candidates.add(ttlPath);
                        }
                    }
                }
            }
        }
        return candidates;
    }

    // VisibleForTesting
    protected long getElapsed(DataNode node) {
        return Time.currentWallTime() - node.stat.getMtime();
    }

}
