/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.highavailability.nonha.embedded;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.leaderelection.LeaderContender;
import org.apache.flink.runtime.leaderelection.LeaderElection;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalListener;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.FutureUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * A simple leader election service, which selects a leader among contenders and notifies listeners.
 *
 * <p>An election service for contenders can be created via {@link #createLeaderElectionService()},
 * a listener service for leader observers can be created via {@link
 * #createLeaderRetrievalService()}.
 */
public class EmbeddedLeaderService {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedLeaderService.class);

    private final Object lock = new Object();

    private final Executor notificationExecutor;

    private final Set<EmbeddedLeaderElection> allLeaderContenders;

    private final Set<EmbeddedLeaderRetrievalService> listeners;

    /** proposed leader, which has been notified of leadership grant, but has not confirmed. */
    private EmbeddedLeaderElection currentLeaderProposed;

    /** actual leader that has confirmed leadership and of which listeners have been notified. */
    private EmbeddedLeaderElection currentLeaderConfirmed;

    /** fencing UID for the current leader (or proposed leader). */
    private volatile UUID currentLeaderSessionId;

    /** the cached address of the current leader. */
    private String currentLeaderAddress;

    /** flag marking the service as terminated. */
    private boolean shutdown;

    // ------------------------------------------------------------------------

    public EmbeddedLeaderService(Executor notificationsDispatcher) {
        this.notificationExecutor = checkNotNull(notificationsDispatcher);
        this.allLeaderContenders = new HashSet<>();
        this.listeners = new HashSet<>();
    }

    // ------------------------------------------------------------------------
    //  shutdown and errors
    // ------------------------------------------------------------------------

    /**
     * Shuts down this leader election service.
     *
     * <p>This method does not perform a clean revocation of the leader status and no notification
     * to any leader listeners. It simply notifies all contenders and listeners that the service is
     * no longer available.
     */
    public void shutdown() {
        synchronized (lock) {
            shutdownInternally(new Exception("Leader election service is shutting down"));
        }
    }

    @VisibleForTesting
    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    private void fatalError(Throwable error) {
        LOG.error(
                "Embedded leader election service encountered a fatal error. Shutting down service.",
                error);

        synchronized (lock) {
            shutdownInternally(
                    new Exception(
                            "Leader election service is shutting down after a fatal error", error));
        }
    }

    @GuardedBy("lock")
    private void shutdownInternally(Exception exceptionForHandlers) {
        Preconditions.checkState(Thread.holdsLock(lock));

        if (!shutdown) {
            // clear all leader status
            currentLeaderProposed = null;
            currentLeaderConfirmed = null;
            currentLeaderSessionId = null;
            currentLeaderAddress = null;

            // fail all registered listeners
            for (EmbeddedLeaderElection leaderElection : allLeaderContenders) {
                leaderElection.shutdown(exceptionForHandlers);
            }
            allLeaderContenders.clear();

            // fail all registered listeners
            for (EmbeddedLeaderRetrievalService service : listeners) {
                service.shutdown(exceptionForHandlers);
            }
            listeners.clear();

            shutdown = true;
        }
    }

    // ------------------------------------------------------------------------
    //  creating contenders and listeners
    // ------------------------------------------------------------------------

    public LeaderElection createLeaderElectionService(String componentId) {
        checkState(!shutdown, "leader election service is shut down");
        return new EmbeddedLeaderElection(componentId);
    }

    public LeaderRetrievalService createLeaderRetrievalService() {
        checkState(!shutdown, "leader election service is shut down");
        return new EmbeddedLeaderRetrievalService();
    }

    // ------------------------------------------------------------------------
    //  adding and removing contenders & listeners
    // ------------------------------------------------------------------------

    /** Callback from leader contenders when they start their service. */
    private void addContender(
            EmbeddedLeaderElection embeddedLeaderElection, LeaderContender contender) {
        synchronized (lock) {
            checkState(!shutdown, "leader election is shut down");
            checkState(!embeddedLeaderElection.running, "leader election is already started");

            try {
                if (!allLeaderContenders.add(embeddedLeaderElection)) {
                    throw new IllegalStateException(
                            "leader election was added to this service multiple times");
                }

                embeddedLeaderElection.contender = contender;
                embeddedLeaderElection.running = true;

                updateLeader()
                        .whenComplete(
                                (aVoid, throwable) -> {
                                    if (throwable != null) {
                                        fatalError(throwable);
                                    }
                                });
            } catch (Throwable t) {
                fatalError(t);
            }
        }
    }

    /** Callback from leader contenders when they stop their service. */
    private void removeContender(EmbeddedLeaderElection embeddedLeaderElection) {
        synchronized (lock) {
            // if the leader election was not even started, simply do nothing
            if (!embeddedLeaderElection.running || shutdown) {
                return;
            }

            try {
                if (!allLeaderContenders.remove(embeddedLeaderElection)) {
                    throw new IllegalStateException(
                            "leader election does not belong to this service");
                }

                // stop the service
                if (embeddedLeaderElection.isLeader) {
                    embeddedLeaderElection.contender.revokeLeadership();
                }
                embeddedLeaderElection.contender = null;
                embeddedLeaderElection.running = false;
                embeddedLeaderElection.isLeader = false;

                // if that was the current leader, unset its status
                if (currentLeaderConfirmed == embeddedLeaderElection) {
                    currentLeaderConfirmed = null;
                    currentLeaderSessionId = null;
                    currentLeaderAddress = null;
                }
                if (currentLeaderProposed == embeddedLeaderElection) {
                    currentLeaderProposed = null;
                    currentLeaderSessionId = null;
                }

                updateLeader()
                        .whenComplete(
                                (aVoid, throwable) -> {
                                    if (throwable != null) {
                                        fatalError(throwable);
                                    }
                                });
            } catch (Throwable t) {
                fatalError(t);
            }
        }
    }

    /** Callback from leader contenders when they confirm a leader grant. */
    private CompletableFuture<Void> confirmLeader(
            final EmbeddedLeaderElection embeddedLeaderElection,
            final UUID leaderSessionId,
            final String leaderAddress) {
        synchronized (lock) {
            // if the leader election was shut down in the meantime, ignore this confirmation
            if (!embeddedLeaderElection.running || shutdown) {
                return FutureUtils.completedVoidFuture();
            }

            try {
                // check if the confirmation is for the same grant, or whether it is a stale grant
                if (embeddedLeaderElection == currentLeaderProposed
                        && currentLeaderSessionId.equals(leaderSessionId)) {
                    LOG.info(
                            "Received confirmation of leadership for leader {} , session={}",
                            leaderAddress,
                            leaderSessionId);

                    // mark leadership
                    currentLeaderConfirmed = embeddedLeaderElection;
                    currentLeaderAddress = leaderAddress;
                    currentLeaderProposed = null;

                    // notify all listeners
                    return notifyAllListeners(leaderAddress, leaderSessionId);
                } else {
                    LOG.debug(
                            "Received confirmation of leadership for a stale leadership grant. Ignoring.");
                }
            } catch (Throwable t) {
                fatalError(t);
            }
        }

        return FutureUtils.completedVoidFuture();
    }

    private CompletableFuture<Void> notifyAllListeners(String address, UUID leaderSessionId) {
        final List<CompletableFuture<Void>> notifyListenerFutures =
                new ArrayList<>(listeners.size());

        for (EmbeddedLeaderRetrievalService listener : listeners) {
            notifyListenerFutures.add(notifyListener(address, leaderSessionId, listener.listener));
        }

        return FutureUtils.waitForAll(notifyListenerFutures);
    }

    @GuardedBy("lock")
    private CompletableFuture<Void> updateLeader() {
        // this must be called under the lock
        Preconditions.checkState(Thread.holdsLock(lock));

        if (currentLeaderConfirmed == null && currentLeaderProposed == null) {
            // we need a new leader
            if (allLeaderContenders.isEmpty()) {
                // no new leader available, tell everyone that there is no leader currently
                return notifyAllListeners(null, null);
            } else {
                // propose a leader and ask it
                final UUID leaderSessionId = UUID.randomUUID();
                EmbeddedLeaderElection embeddedLeaderElection =
                        allLeaderContenders.iterator().next();

                currentLeaderSessionId = leaderSessionId;
                currentLeaderProposed = embeddedLeaderElection;
                currentLeaderProposed.isLeader = true;

                LOG.info(
                        "Proposing leadership to the contender that is registered under component ID '{}'.",
                        embeddedLeaderElection.componentId);

                return execute(
                        new GrantLeadershipCall(
                                embeddedLeaderElection.contender, leaderSessionId, LOG));
            }
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> notifyListener(
            @Nullable String address,
            @Nullable UUID leaderSessionId,
            LeaderRetrievalListener listener) {
        return CompletableFuture.runAsync(
                new NotifyOfLeaderCall(address, leaderSessionId, listener, LOG),
                notificationExecutor);
    }

    private void addListener(
            EmbeddedLeaderRetrievalService service, LeaderRetrievalListener listener) {
        synchronized (lock) {
            checkState(!shutdown, "leader election service is shut down");
            checkState(!service.running, "leader retrieval service is already started");

            try {
                if (!listeners.add(service)) {
                    throw new IllegalStateException(
                            "leader retrieval service was added to this service multiple times");
                }

                service.listener = listener;
                service.running = true;

                // if we already have a leader, immediately notify this new listener
                if (currentLeaderConfirmed != null) {
                    notifyListener(currentLeaderAddress, currentLeaderSessionId, listener);
                }
            } catch (Throwable t) {
                fatalError(t);
            }
        }
    }

    private void removeListener(EmbeddedLeaderRetrievalService service) {
        synchronized (lock) {
            // if the service was not even started, simply do nothing
            if (!service.running || shutdown) {
                return;
            }

            try {
                if (!listeners.remove(service)) {
                    throw new IllegalStateException(
                            "leader retrieval service does not belong to this service");
                }

                // stop the service
                service.listener = null;
                service.running = false;
            } catch (Throwable t) {
                fatalError(t);
            }
        }
    }

    @VisibleForTesting
    CompletableFuture<Void> grantLeadership() {
        synchronized (lock) {
            if (shutdown) {
                return getShutDownFuture();
            }

            return updateLeader();
        }
    }

    private CompletableFuture<Void> getShutDownFuture() {
        return FutureUtils.completedExceptionally(
                new FlinkException("EmbeddedLeaderService has been shut down."));
    }

    @VisibleForTesting
    CompletableFuture<Void> revokeLeadership() {
        synchronized (lock) {
            if (shutdown) {
                return getShutDownFuture();
            }

            if (currentLeaderProposed != null || currentLeaderConfirmed != null) {
                final EmbeddedLeaderElection embeddedLeaderElection;

                if (currentLeaderConfirmed != null) {
                    embeddedLeaderElection = currentLeaderConfirmed;
                } else {
                    embeddedLeaderElection = currentLeaderProposed;
                }

                LOG.info("Revoking leadership of {}.", embeddedLeaderElection.contender);
                embeddedLeaderElection.isLeader = false;
                CompletableFuture<Void> revokeLeadershipCallFuture =
                        execute(new RevokeLeadershipCall(embeddedLeaderElection.contender));

                CompletableFuture<Void> notifyAllListenersFuture = notifyAllListeners(null, null);

                currentLeaderProposed = null;
                currentLeaderConfirmed = null;
                currentLeaderAddress = null;
                currentLeaderSessionId = null;

                return CompletableFuture.allOf(
                        revokeLeadershipCallFuture, notifyAllListenersFuture);
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private CompletableFuture<Void> execute(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, notificationExecutor);
    }

    // ------------------------------------------------------------------------
    //  election and retrieval service implementations
    // ------------------------------------------------------------------------

    private class EmbeddedLeaderElection implements LeaderElection {

        final String componentId;
        volatile LeaderContender contender;

        volatile boolean isLeader;

        volatile boolean running;

        EmbeddedLeaderElection(String componentId) {
            this.componentId = componentId;
        }

        @Override
        public void startLeaderElection(LeaderContender contender) throws Exception {
            checkNotNull(contender);
            addContender(this, contender);
        }

        @Override
        public void close() {
            removeContender(this);
        }

        @Override
        public CompletableFuture<Void> confirmLeadershipAsync(
                UUID leaderSessionID, String leaderAddress) {
            checkNotNull(leaderSessionID);
            checkNotNull(leaderAddress);
            return confirmLeader(this, leaderSessionID, leaderAddress);
        }

        @Override
        public CompletableFuture<Boolean> hasLeadershipAsync(UUID leaderSessionId) {
            return CompletableFuture.completedFuture(
                    isLeader && leaderSessionId.equals(currentLeaderSessionId));
        }

        void shutdown(Exception cause) {
            if (running) {
                running = false;
                isLeader = false;
                contender.revokeLeadership();
                contender = null;
            }
        }
    }

    // ------------------------------------------------------------------------

    private class EmbeddedLeaderRetrievalService implements LeaderRetrievalService {

        volatile LeaderRetrievalListener listener;

        volatile boolean running;

        @Override
        public void start(LeaderRetrievalListener listener) throws Exception {
            checkNotNull(listener);
            addListener(this, listener);
        }

        @Override
        public void stop() throws Exception {
            removeListener(this);
        }

        public void shutdown(Exception cause) {
            if (running) {
                running = false;
                listener = null;
            }
        }
    }

    // ------------------------------------------------------------------------
    //  asynchronous notifications
    // ------------------------------------------------------------------------

    private static class NotifyOfLeaderCall implements Runnable {

        @Nullable private final String address; // null if leader revoked without new leader
        @Nullable private final UUID leaderSessionId; // null if leader revoked without new leader

        private final LeaderRetrievalListener listener;
        private final Logger logger;

        NotifyOfLeaderCall(
                @Nullable String address,
                @Nullable UUID leaderSessionId,
                LeaderRetrievalListener listener,
                Logger logger) {

            this.address = address;
            this.leaderSessionId = leaderSessionId;
            this.listener = checkNotNull(listener);
            this.logger = checkNotNull(logger);
        }

        @Override
        public void run() {
            try {
                listener.notifyLeaderAddress(address, leaderSessionId);
            } catch (Throwable t) {
                logger.warn("Error notifying leader listener about new leader", t);
                listener.handleError(t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }
    }

    // ------------------------------------------------------------------------

    private static class GrantLeadershipCall implements Runnable {

        private final LeaderContender contender;
        private final UUID leaderSessionId;
        private final Logger logger;

        GrantLeadershipCall(LeaderContender contender, UUID leaderSessionId, Logger logger) {

            this.contender = checkNotNull(contender);
            this.leaderSessionId = checkNotNull(leaderSessionId);
            this.logger = checkNotNull(logger);
        }

        @Override
        public void run() {
            try {
                contender.grantLeadership(leaderSessionId);
            } catch (Throwable t) {
                logger.warn("Error granting leadership to contender", t);
                contender.handleError(t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }
    }

    private static class RevokeLeadershipCall implements Runnable {

        @Nonnull private final LeaderContender contender;

        RevokeLeadershipCall(@Nonnull LeaderContender contender) {
            this.contender = contender;
        }

        @Override
        public void run() {
            contender.revokeLeadership();
        }
    }
}
