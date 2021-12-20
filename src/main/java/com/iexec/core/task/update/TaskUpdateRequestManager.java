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

import com.iexec.core.task.Task;
import com.iexec.core.task.TaskService;
import com.iexec.core.utils.TaskExecutorUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.expiringmap.ExpiringMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    /**
     * An XL task timeout happens after 100 hours.
     */
    private static final long LONGEST_TASK_TIMEOUT = 100;
    /**
     * Max number of threads to update task for each core.
     */
    private static final int TASK_UPDATE_THREADS_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final BlockingQueue<Task> queue = createQueue();
    private final AtomicInteger updatingThreads = new AtomicInteger(0);

    private final ConcurrentMap<String, Object> locks = ExpiringMap.builder()
            .expiration(LONGEST_TASK_TIMEOUT, TimeUnit.HOURS)
            .build();
    private final Executor taskUpdateExecutor = TaskExecutorUtils.newThreadPoolTaskExecutor(
            "task-update-",
            TASK_UPDATE_THREADS_POOL_SIZE);
    private TaskUpdateRequestConsumer consumer;

    private final TaskService taskService;

    public TaskUpdateRequestManager(TaskService taskService) {
        this.taskService = taskService;
    }

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
            if (queue.stream().anyMatch(task -> chainTaskId.equals(task.getChainTaskId()))){
                log.warn("Request already published [chainTaskId:{}]", chainTaskId);
                return false;
            }
            final Optional<Task> task = taskService.getTaskByChainTaskId(chainTaskId);
            if (task.isEmpty()) {
                log.warn("No such task. [chainTaskId: {}]", chainTaskId);
                return false;
            }

            boolean isOffered = queue.offer(task.get());
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
    @SneakyThrows
    public void consumeAndNotify() {
        if (consumer == null){
            log.warn("Waiting for consumer before consuming [queueSize:{}]", queue.size());
            return;
        }

        // We should only start threads to complete the thread pool.
        // This is useless for a fresh start but could be handy
        // if current thread is stopped and current method is rescheduled.

        // Note that we start a bunch of threads at a time,
        // so we're sure we don't have more than this number of requests to handle
        // before handling a priority request.
        final int nbThreadsToStart = TASK_UPDATE_THREADS_POOL_SIZE - updatingThreads.get();
        for (int i = 0; i < nbThreadsToStart; i++) {
            startTaskUpdateThread();
        }
    }

    private void startTaskUpdateThread() {
        // When a task update is achieved or if there's an issue,
        // we can start another task update.
        // This should ensure a constant number of waiting and running threads.
        CompletableFuture.runAsync(this::waitForTaskUpdateRequest, taskUpdateExecutor)
                .thenRunAsync(this::startTaskUpdateThread);
    }

    /**
     * Wait for new task update request and run the update once a request is arrived.
     * 2 requests can be run in parallel if they don't target the same task.
     * <br>
     * Interrupts current thread if queue taking has been stopped during the execution.
     */
    private void waitForTaskUpdateRequest() {
        updatingThreads.incrementAndGet();
        log.info("Waiting requests from publisher [queueSize:{}]", queue.size());
        try {
            final Task task = queue.take();
            String chainTaskId = task.getChainTaskId();
            log.info("Selected task [chainTaskId: {}, status: {}]", chainTaskId, task.getCurrentStatus());
            synchronized (locks.computeIfAbsent(chainTaskId, key -> new Object())) { // require one update on a same task at a time
                consumer.onTaskUpdateRequest(chainTaskId); // synchronously update task
            }
        } catch (InterruptedException e) {
            log.error("The unexpected happened", e);
            Thread.currentThread().interrupt();
        } finally {
            updatingThreads.decrementAndGet();
        }
    }

    PriorityBlockingQueue<Task> createQueue() {
        // Tasks whose status are the more advanced should be computed before others
        // Same goes for tasks whose contribution deadline is soon
        final Comparator<Task> comparator = Comparator.comparing(Task::getCurrentStatus, Comparator.reverseOrder())
                .thenComparing(Task::getContributionDeadline);
        return new PriorityBlockingQueue<>(
                TASK_UPDATE_THREADS_POOL_SIZE,
                comparator
        );
    }
}
