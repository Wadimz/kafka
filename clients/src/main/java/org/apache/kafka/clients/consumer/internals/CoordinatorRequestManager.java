/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.message.FindCoordinatorRequestData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.FindCoordinatorResponse;
import org.apache.kafka.common.utils.LogContext;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

import static org.apache.kafka.clients.consumer.internals.NetworkClientDelegate.PollResult.EMPTY;

/**
 * This is responsible for timing to send the next {@link FindCoordinatorRequest} based on the following criteria:
 * <p/>
 * Whether there is an existing coordinator.
 * Whether there is an inflight request.
 * Whether the backoff timer has expired.
 * The {@link NetworkClientDelegate.PollResult} contains either a wait timer
 * or a singleton list of {@link NetworkClientDelegate.UnsentRequest}.
 * <p/>
 * The {@link FindCoordinatorRequest} will be handled by the {@link #onResponse(long, FindCoordinatorResponse)}  callback, which
 * subsequently invokes {@code onResponse} to handle the exception and response. Note that the coordinator node will be
 * marked {@code null} upon receiving a failure.
 */
public class CoordinatorRequestManager implements RequestManager {
    private static final long COORDINATOR_DISCONNECT_LOGGING_INTERVAL_MS = 60 * 1000;
    private final Logger log;
    private final String groupId;

    private final RequestState coordinatorRequestState;
    private long timeMarkedUnknownMs = -1L; // starting logging a warning only after unable to connect for a while
    private long totalDisconnectedMin = 0;
    private boolean closing = false;
    private Node coordinator;
    // Hold the latest fatal error received. It is exposed so that managers requiring a coordinator can access it and take 
    // appropriate actions. 
    // For example:
    // - AbstractHeartbeatRequestManager propagates the error event to the application thread.
    // - CommitRequestManager fail pending requests.
    private Optional<Throwable> fatalError = Optional.empty();

    public CoordinatorRequestManager(
        final LogContext logContext,
        final long retryBackoffMs,
        final long retryBackoffMaxMs,
        final String groupId
    ) {
        Objects.requireNonNull(groupId);
        this.log = logContext.logger(this.getClass());
        this.groupId = groupId;
        this.coordinatorRequestState = new RequestState(
                logContext,
                CoordinatorRequestManager.class.getSimpleName(),
                retryBackoffMs,
                retryBackoffMaxMs
        );
    }

    @Override
    public void signalClose() {
        closing = true;
    }

    /**
     * Poll for the FindCoordinator request.
     * If we don't need to discover a coordinator, this method will return a PollResult with Long.MAX_VALUE backoff time and an empty list.
     * If we are still backing off from a previous attempt, this method will return a PollResult with the remaining backoff time and an empty list.
     * Otherwise, this returns will return a PollResult with a singleton list of UnsentRequest and Long.MAX_VALUE backoff time.
     * Note that this method does not involve any actual network IO, and it only determines if we need to send a new request or not.
     *
     * @param currentTimeMs current time in ms.
     * @return {@link NetworkClientDelegate.PollResult}. This will not be {@code null}.
     */
    @Override
    public NetworkClientDelegate.PollResult poll(final long currentTimeMs) {
        if (closing || this.coordinator != null)
            return EMPTY;

        if (coordinatorRequestState.canSendRequest(currentTimeMs)) {
            NetworkClientDelegate.UnsentRequest request = makeFindCoordinatorRequest(currentTimeMs);
            return new NetworkClientDelegate.PollResult(request);
        }

        return new NetworkClientDelegate.PollResult(coordinatorRequestState.remainingBackoffMs(currentTimeMs));
    }

    NetworkClientDelegate.UnsentRequest makeFindCoordinatorRequest(final long currentTimeMs) {
        coordinatorRequestState.onSendAttempt(currentTimeMs);
        FindCoordinatorRequestData data = new FindCoordinatorRequestData()
                .setKeyType(FindCoordinatorRequest.CoordinatorType.GROUP.id())
                .setKey(this.groupId);
        NetworkClientDelegate.UnsentRequest unsentRequest = new NetworkClientDelegate.UnsentRequest(
            new FindCoordinatorRequest.Builder(data),
            Optional.empty()
        );

        return unsentRequest.whenComplete((clientResponse, throwable) -> {
            getAndClearFatalError();
            if (clientResponse != null) {
                FindCoordinatorResponse response = (FindCoordinatorResponse) clientResponse.responseBody();
                onResponse(clientResponse.receivedTimeMs(), response);
            } else {
                onFailedResponse(unsentRequest.handler().completionTimeMs(), throwable);
            }
        });
    }

    /**
     * Handles the disconnection of the current coordinator.
     * This method checks if the given exception is an instance of {@link DisconnectException}.
     * If so, it marks the coordinator as unknown, indicating that the client should
     * attempt to discover a new coordinator. For any other exception type, no action is performed.
     *
     * @param exception     The exception to handle, which was received as part of a request response.
     * @param currentTimeMs The current time in milliseconds.
     */
    public void handleCoordinatorDisconnect(Throwable exception, long currentTimeMs) {
        if (exception instanceof DisconnectException) {
            markCoordinatorUnknown(exception.getMessage(), currentTimeMs);
        }
    }

    /**
     * Mark the coordinator as "unknown" (i.e. {@code null}) when a disconnect is detected. This detection can occur
     * in one of two paths:
     *
     * <ol>
     *     <li>The coordinator was discovered, but then later disconnected</li>
     *     <li>The coordinator has not yet been discovered and/or connected</li>
     * </ol>
     *
     * @param cause         String explanation of why the coordinator is marked unknown
     * @param currentTimeMs Current time in milliseconds
     */
    public void markCoordinatorUnknown(final String cause, final long currentTimeMs) {
        if (coordinator != null || timeMarkedUnknownMs == -1) {
            timeMarkedUnknownMs = currentTimeMs;
            totalDisconnectedMin = 0;
        }

        if (coordinator != null) {
            log.info(
                "Group coordinator {} is unavailable or invalid due to cause: {}. Rediscovery will be attempted.",
                coordinator,
                cause
            );
            coordinator = null;
        } else {
            long durationOfOngoingDisconnectMs = Math.max(0, currentTimeMs - timeMarkedUnknownMs);
            long currDisconnectMin = durationOfOngoingDisconnectMs / COORDINATOR_DISCONNECT_LOGGING_INTERVAL_MS;
            if (currDisconnectMin > totalDisconnectedMin) {
                log.warn("Consumer has been disconnected from the group coordinator for {}ms", durationOfOngoingDisconnectMs);
                totalDisconnectedMin = currDisconnectMin;
            }
        }
    }

    private void onSuccessfulResponse(
        final long currentTimeMs,
        final FindCoordinatorResponseData.Coordinator coordinator
    ) {
        // use MAX_VALUE - node.id as the coordinator id to allow separate connections
        // for the coordinator in the underlying network client layer
        int coordinatorConnectionId = Integer.MAX_VALUE - coordinator.nodeId();
        this.coordinator = new Node(
                coordinatorConnectionId,
                coordinator.host(),
                coordinator.port());
        log.info("Discovered group coordinator {}", coordinator);
        coordinatorRequestState.onSuccessfulAttempt(currentTimeMs);
    }

    private void onFailedResponse(final long currentTimeMs, final Throwable exception) {
        coordinatorRequestState.onFailedAttempt(currentTimeMs);
        markCoordinatorUnknown("FindCoordinator failed with exception", currentTimeMs);

        if (exception instanceof RetriableException) {
            log.debug("FindCoordinator request failed due to retriable exception", exception);
            return;
        }

        if (exception == Errors.GROUP_AUTHORIZATION_FAILED.exception()) {
            log.debug("FindCoordinator request failed due to authorization error {}", exception.getMessage());
            KafkaException groupAuthorizationException = GroupAuthorizationException.forGroupId(this.groupId);
            fatalError = Optional.of(groupAuthorizationException);
            return;
        }

        log.warn("FindCoordinator request failed due to fatal exception", exception);
        fatalError = Optional.of(exception);
    }

    /**
     * Handles the response upon completing the {@link FindCoordinatorRequest} if the
     * future returned successfully. This method must still unwrap the response object
     * to check for protocol errors.
     *
     * @param currentTimeMs current time in ms.
     * @param response      the response for finding the coordinator. null if an exception is thrown.
     */
    private void onResponse(
        final long currentTimeMs,
        final FindCoordinatorResponse response
    ) {
        // handles Runtime exception
        Optional<FindCoordinatorResponseData.Coordinator> coordinator = response.coordinatorByKey(this.groupId);
        if (coordinator.isEmpty()) {
            String msg = String.format("Response did not contain expected coordinator section for groupId: %s", this.groupId);
            onFailedResponse(currentTimeMs, new IllegalStateException(msg));
            return;
        }

        FindCoordinatorResponseData.Coordinator node = coordinator.get();
        if (node.errorCode() != Errors.NONE.code()) {
            onFailedResponse(currentTimeMs, Errors.forCode(node.errorCode()).exception());
            return;
        }
        onSuccessfulResponse(currentTimeMs, node);
    }

    /**
     * Returns the current coordinator node.
     *
     * @return the current coordinator node.
     */
    public Optional<Node> coordinator() {
        return Optional.ofNullable(this.coordinator);
    }
    
    public Optional<Throwable> getAndClearFatalError() {
        Optional<Throwable> fatalError = this.fatalError;
        this.fatalError = Optional.empty();
        return fatalError;
    }

    public Optional<Throwable> fatalError() {
        return fatalError;
    }
}
