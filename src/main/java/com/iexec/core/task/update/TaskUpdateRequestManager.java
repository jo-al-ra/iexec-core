/*
 * Copyright 2020 IEXEC BLOCKCHAIN TECH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iexec.core.task.update;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * This class is used to perform updates on a task one by one.
 * It also ensures that no extra update is performed for no reason
 * (in the case of multiple replicate updates in a short time,
 * the task update will only be called once)
 */
@Slf4j
@Component
public class TaskUpdateRequestManager {

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private TaskUpdateRequestConsumer consumer;

    /**
     * Publish TaskUpdateRequest async
     * @param chainTaskId
     * @return
     */
    public CompletableFuture<Boolean> publishRequest(String chainTaskId) {
        Supplier<Boolean> publishRequest = () -> {
            if (chainTaskId.isEmpty()){
                return false;
            }
            if (queue.contains(chainTaskId)){
                log.warn("Request already published [chainTaskId:{}]", chainTaskId);
                return false;
            }
            boolean isOffered = queue.offer(chainTaskId);
            log.info("Published task update request [chainTaskId:{}, queueSize:{}]", chainTaskId, queue.size());
            return isOffered;
        };
        return CompletableFuture.supplyAsync(publishRequest, executorService);
    }

    /**
     * Authorize one TaskUpdateRequest consumer subscription at a time.
     * @param consumer
     * @return
     */
    public void setRequestConsumer(final TaskUpdateRequestConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * De-queues head anf notifies consumer.
     *
     * Retries consuming and notifying if interrupted
     */
    @Scheduled(fixedDelay = 1000)
    public void consumeAndNotify() {
        if (consumer == null){
            log.warn("Waiting for consumer before consuming [queueSize:{}]", queue.size());
            return;
        }

        while (!Thread.currentThread().isInterrupted()){
            waitForTaskUpdateRequest();
        }
    }

    /**
     * Wait for new task update request and run the update once a request is arrived.
     * 2 requests can be run in parallel if they don't target the same task.
     * <br>
     * Interrupts current thread if queue taking has been stopped during the execution.
     */
    private void waitForTaskUpdateRequest() {
        log.info("Waiting requests from publisher [queueSize:{}]", queue.size());
        try {
            String chainTaskId = queue.take();
            CompletableFuture.runAsync(() -> {
                locks.putIfAbsent(chainTaskId, new Object()); // create lock if necessary
                System.out.println(chainTaskId + ":"  + Thread.currentThread().getName());
                synchronized (locks.get(chainTaskId)){ // require one update on a same task at a time
                    consumer.onTaskUpdateRequest(chainTaskId); // synchronously update task
                }
                if (!queue.contains(chainTaskId)){
                    locks.remove(chainTaskId);  // prune task lock if not required anymore
                }
            });
        } catch (InterruptedException e) {
            log.error("The unexpected happened", e);
            Thread.currentThread().interrupt();
        }
    }
}
