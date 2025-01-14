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

package com.iexec.core.replicate;

import com.iexec.common.chain.WorkerpoolAuthorization;
import com.iexec.common.notification.TaskNotification;
import com.iexec.common.notification.TaskNotificationExtra;
import com.iexec.common.notification.TaskNotificationType;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.replicate.ReplicateStatusDetails;
import com.iexec.common.replicate.ReplicateStatusUpdate;
import com.iexec.common.task.TaskAbortCause;
import com.iexec.core.chain.SignatureService;
import com.iexec.core.chain.Web3jService;
import com.iexec.core.contribution.ConsensusHelper;
import com.iexec.core.task.Task;
import com.iexec.core.task.TaskService;
import com.iexec.core.task.TaskStatus;
import com.iexec.core.task.update.TaskUpdateRequestManager;
import com.iexec.core.worker.Worker;
import com.iexec.core.worker.WorkerService;
import net.jodah.expiringmap.ExpiringMap;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.iexec.common.replicate.ReplicateStatus.*;
import static com.iexec.core.task.Task.LONGEST_TASK_TIMEOUT;


@Service
public class ReplicateSupplyService {

    private final ReplicatesService replicatesService;
    private final SignatureService signatureService;
    private final TaskService taskService;
    private final TaskUpdateRequestManager taskUpdateRequestManager;
    private final WorkerService workerService;
    private final Web3jService web3jService;
    final Map<String, Lock> taskAccessForNewReplicateLocks =
            ExpiringMap.builder()
                    .expiration(LONGEST_TASK_TIMEOUT.getSeconds(), TimeUnit.SECONDS)
                    .build();

    public ReplicateSupplyService(ReplicatesService replicatesService,
                                  SignatureService signatureService,
                                  TaskService taskService,
                                  TaskUpdateRequestManager taskUpdateRequestManager,
                                  WorkerService workerService,
                                  Web3jService web3jService) {
        this.replicatesService = replicatesService;
        this.signatureService = signatureService;
        this.taskService = taskService;
        this.taskUpdateRequestManager = taskUpdateRequestManager;
        this.workerService = workerService;
        this.web3jService = web3jService;
    }

    /*
     * #1 Retryable - In case the task has been modified between reading and writing it, it is retried up to 5 times
     *
     * #2 TaskAccessForNewReplicateLock - To avoid the case where only 1 replicate is required but 2 replicates are
     * created since 2 workers are calling getAvailableReplicate() and reading the database at the same time, we need a
     *  'TaskAccessForNewReplicateLock' which should be:
     *  - locked before `replicatesService.moreReplicatesNeeded(..)`
     *  - released after `replicatesService.addNewReplicate(..)` in the best scenario
     *  - released before any `continue` or  `return`
     *
     */
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 5)
    Optional<WorkerpoolAuthorization> getAuthOfAvailableReplicate(long workerLastBlock, String walletAddress) {
        // return empty if max computing task is reached or if the worker is not found
        if (!workerService.canAcceptMoreWorks(walletAddress)) {
            return Optional.empty();
        }

        // return empty if the worker is not sync
        //TODO Check if worker node is sync
        boolean isWorkerLastBlockAvailable = workerLastBlock > 0;
        if (!isWorkerLastBlockAvailable) {
            return Optional.empty();
        }

        if (!web3jService.hasEnoughGas(walletAddress)) {
            return Optional.empty();
        }

        // TODO : Remove this, the optional can never be empty
        // This is covered in workerService.canAcceptMoreWorks
        Optional<Worker> optional = workerService.getWorker(walletAddress);
        if (optional.isEmpty()) {
            return Optional.empty();
        }
        Worker worker = optional.get();

        return getAuthorizationForAnyAvailableTask(
                walletAddress,
                worker.isTeeEnabled()
        );
    }

    /**
     * Loops through available tasks
     * and finds the first one that needs a new {@link Replicate}.
     *
     * @param walletAddress Wallet address of the worker asking for work.
     * @param isTeeEnabled  Whether this worker supports TEE.
     * @return An {@link Optional} containing a {@link WorkerpoolAuthorization}
     * if any {@link Task} is available and can be handled by this worker,
     * {@link Optional#empty()} otherwise.
     */
    private Optional<WorkerpoolAuthorization> getAuthorizationForAnyAvailableTask(
            String walletAddress,
            boolean isTeeEnabled) {
        final List<String> alreadyScannedTasks = new ArrayList<>();

        Optional<WorkerpoolAuthorization> authorization = Optional.empty();
        while (authorization.isEmpty()) {
            final Optional<Task> oTask = taskService.getPrioritizedInitializedOrRunningTask(
                    !isTeeEnabled,
                    alreadyScannedTasks
            );
            if (oTask.isEmpty()) {
                // No more tasks waiting for a new replicate.
                return Optional.empty();
            }

            final Task task = oTask.get();
            alreadyScannedTasks.add(task.getChainTaskId());
            authorization = getAuthorizationForTask(task, walletAddress);
        }
        return authorization;
    }

    private Optional<WorkerpoolAuthorization> getAuthorizationForTask(Task task, String walletAddress) {
        String chainTaskId = task.getChainTaskId();
        if (!acceptOrRejectTask(task, walletAddress)) {
            return Optional.empty();
        }

        // generate contribution authorization
        final WorkerpoolAuthorization authorization = signatureService.createAuthorization(
                walletAddress,
                chainTaskId,
                task.getEnclaveChallenge());
        return Optional.of(authorization);
    }

    /**
     * Given a {@link Task} and a {@code walletAddress} of a worker,
     * tries to accept the task - i.e. create a new {@link Replicate}
     * for that task on that worker.
     *
     * @param task  {@link Task} needing at least one new {@link Replicate}.
     * @param walletAddress Wallet address of a worker looking for new {@link Task}.
     * @return {@literal true} if the task has been accepted,
     * {@literal false} otherwise.
     */
    private boolean acceptOrRejectTask(Task task, String walletAddress) {
        if (!workerService.isAllowedToJoin(walletAddress)) {
            workerService.deleteWorkerByAddress(walletAddress);
            return false;
        }
        if (task.getEnclaveChallenge().isEmpty()) {
            return false;
        }

        final String chainTaskId = task.getChainTaskId();
        final Optional<ReplicatesList> oReplicatesList = replicatesService.getReplicatesList(chainTaskId);
        // Check is only here to prevent
        // "`Optional.get()` without `isPresent()` warning".
        // This case should not happen.
        if (oReplicatesList.isEmpty()) {
            return false;
        }

        final ReplicatesList replicatesList = oReplicatesList.get();

        final boolean hasWorkerAlreadyParticipated =
                replicatesList.hasWorkerAlreadyParticipated(walletAddress);
        if (hasWorkerAlreadyParticipated) {
            return false;
        }

        final Lock lock = taskAccessForNewReplicateLocks
                .computeIfAbsent(chainTaskId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            // Can't get lock on task
            // => another replicate is already having a look at this task.
            return false;
        }

        try {
            final boolean taskNeedsMoreContributions = ConsensusHelper.doesTaskNeedMoreContributionsForConsensus(
                    chainTaskId,
                    replicatesList.getReplicates(),
                    task.getTrust(),
                    task.getMaxExecutionTime());

            if (!taskNeedsMoreContributions
                    || taskService.isConsensusReached(replicatesList)) {
                return false;
            }

            replicatesService.addNewReplicate(chainTaskId, walletAddress);
            workerService.addChainTaskIdToWorker(chainTaskId, walletAddress);
        } finally {
            // We should always unlock the task
            // so that it could be taken by another replicate
            // if there's any issue.
            lock.unlock();
        }

        return true;
    }

    /**
     * Get notifications missed by the worker during the time it was absent.
     * 
     * @param blockNumber last seen blocknumber by the worker
     * @param walletAddress of the worker
     * @return list of missed notifications. Can be empty if no notification is found
     */
    public List<TaskNotification> getMissedTaskNotifications(long blockNumber, String walletAddress) {
        List<String> chainTaskIdList = workerService.getChainTaskIds(walletAddress);
        List<Task> tasksWithWorkerParticipation = taskService.getTasksByChainTaskIds(chainTaskIdList);
        List<TaskNotification> taskNotifications = new ArrayList<>();
        for (Task task : tasksWithWorkerParticipation) {
            String chainTaskId = task.getChainTaskId();

            Optional<Replicate> oReplicate = replicatesService.getReplicate(chainTaskId, walletAddress);
            if (oReplicate.isEmpty()) {
                continue;
            }
            Replicate replicate = oReplicate.get();
            boolean isRecoverable = replicate.isRecoverable();
            if (!isRecoverable) {
                continue;
            }
            String enclaveChallenge = task.getEnclaveChallenge();
            if (task.isTeeTask() && enclaveChallenge.isEmpty()) {
                continue;
            }
            Optional<TaskNotificationType> taskNotificationType = getTaskNotificationType(task, replicate, blockNumber);
            if (taskNotificationType.isEmpty()) {
                continue;
            }
            TaskNotificationExtra taskNotificationExtra =
                    getTaskNotificationExtra(task, taskNotificationType.get(),  walletAddress, enclaveChallenge);

            TaskNotification taskNotification = TaskNotification.builder()
                    .chainTaskId(chainTaskId)
                    .workersAddress(Collections.singletonList(walletAddress))
                    .taskNotificationType(taskNotificationType.get())
                    .taskNotificationExtra(taskNotificationExtra)
                    .build();

            // change replicate status
            ReplicateStatusUpdate statusUpdate = ReplicateStatusUpdate.poolManagerRequest(RECOVERING);
            replicatesService.updateReplicateStatus(chainTaskId, walletAddress, statusUpdate);

            taskNotifications.add(taskNotification);
        }

        return taskNotifications;
    }

    private TaskNotificationExtra getTaskNotificationExtra(Task task, TaskNotificationType taskNotificationType, String walletAddress, String enclaveChallenge) {
        TaskNotificationExtra taskNotificationExtra = TaskNotificationExtra.builder().build();

        switch (taskNotificationType){
            case PLEASE_CONTRIBUTE:
                WorkerpoolAuthorization authorization = signatureService.createAuthorization(
                        walletAddress, task.getChainTaskId(), enclaveChallenge);
                taskNotificationExtra.setWorkerpoolAuthorization(authorization);
                break;
            case PLEASE_REVEAL:
                taskNotificationExtra.setBlockNumber(task.getConsensusReachedBlockNumber());
                break;
            case PLEASE_ABORT:
                taskNotificationExtra.setTaskAbortCause(getTaskAbortCause(task));
                break;
            default:
                break;
        }
        return taskNotificationExtra;
    }

    public Optional<TaskNotificationType> getTaskNotificationType(Task task, Replicate replicate, long blockNumber) {

        if (task.inContributionPhase()) {
            return recoverReplicateInContributionPhase(task, replicate, blockNumber);
        }
        // CONTRIBUTION_TIMEOUT or CONSENSUS_REACHED without contribution
        if (task.getCurrentStatus().equals(TaskStatus.CONTRIBUTION_TIMEOUT)
                || (task.getCurrentStatus().equals(TaskStatus.CONSENSUS_REACHED)
                        && !replicate.containsContributedStatus())) {
            return Optional.of(TaskNotificationType.PLEASE_ABORT);
        }

        Optional<TaskNotificationType> oRecoveryAction = Optional.empty();

        if (task.inRevealPhase()) {
            oRecoveryAction = recoverReplicateInRevealPhase(task, replicate, blockNumber);
        }

        if (task.inResultUploadPhase()) {
            oRecoveryAction = recoverReplicateInResultUploadPhase(task, replicate);
        }

        if (task.inCompletionPhase()) {
            return recoverReplicateIfRevealed(replicate);
        }

        return oRecoveryAction;
    }

    /**
     * CREATED, ..., CAN_CONTRIBUTE         => TaskNotificationType.PLEASE_CONTRIBUTE
     * CONTRIBUTING + !onChain              => TaskNotificationType.PLEASE_CONTRIBUTE
     * CONTRIBUTING + done onChain          => updateStatus to CONTRIBUTED & go to next case
     * CONTRIBUTED + !CONSENSUS_REACHED     => TaskNotificationType.PLEASE_WAIT
     * CONTRIBUTED + CONSENSUS_REACHED      => TaskNotificationType.PLEASE_REVEAL
     */

    private Optional<TaskNotificationType> recoverReplicateInContributionPhase(Task task, Replicate replicate, long blockNumber) {
        String chainTaskId = task.getChainTaskId();
        String walletAddress = replicate.getWalletAddress();

        if (replicate.getLastRelevantStatus().isEmpty()) {
            return Optional.empty();
        }

        boolean beforeContributing = replicate.isBeforeStatus(ReplicateStatus.CONTRIBUTING);
        boolean didReplicateStartContributing = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.CONTRIBUTING);
        boolean didReplicateContributeOnChain = replicatesService.didReplicateContributeOnchain(chainTaskId, walletAddress);

        if (beforeContributing) {
            return Optional.of(TaskNotificationType.PLEASE_CONTRIBUTE);
        }

        if (didReplicateStartContributing && !didReplicateContributeOnChain) {
            return Optional.of(TaskNotificationType.PLEASE_CONTRIBUTE);
        }

        if (didReplicateStartContributing && didReplicateContributeOnChain) {
            ReplicateStatusDetails details = new ReplicateStatusDetails(blockNumber);
            replicatesService.updateReplicateStatus(chainTaskId, walletAddress, CONTRIBUTED, details);
        }

        // we read the replicate from db to consider the changes added in the previous case
        Optional<Replicate> oReplicateWithLatestChanges = replicatesService.getReplicate(chainTaskId, walletAddress);
        if (oReplicateWithLatestChanges.isEmpty()) {
            return Optional.empty();
        }

        Replicate replicateWithLatestChanges = oReplicateWithLatestChanges.get();
        if (replicateWithLatestChanges.getLastRelevantStatus().isEmpty()) {
            return Optional.empty();
        }

        boolean didReplicateContribute = replicateWithLatestChanges.getLastRelevantStatus().get()
                .equals(ReplicateStatus.CONTRIBUTED);

        if (didReplicateContribute) {
            final Optional<ReplicatesList> oReplicatesList = replicatesService.getReplicatesList(chainTaskId);
            if (oReplicatesList.isEmpty()) {
                return Optional.empty();
            }
            if (!taskService.isConsensusReached(oReplicatesList.get())) {
                return Optional.of(TaskNotificationType.PLEASE_WAIT);
            }

            taskUpdateRequestManager.publishRequest(chainTaskId);
            return Optional.of(TaskNotificationType.PLEASE_REVEAL);
        }

        return Optional.empty();
    }

    /**
     * CONTRIBUTED                      => TaskNotificationType.PLEASE_REVEAL
     * REVEALING + !onChain             => TaskNotificationType.PLEASE_REVEAL
     * REVEALING + done onChain         => update replicateStatus to REVEALED, update task & go to next case
     * REVEALED (no upload req)         => TaskNotificationType.PLEASE_WAIT
     * RESULT_UPLOAD_REQUESTED          => TaskNotificationType.PLEASE_UPLOAD_RESULT
     */

    private Optional<TaskNotificationType> recoverReplicateInRevealPhase(Task task, Replicate replicate, long blockNumber) {
        String chainTaskId = task.getChainTaskId();
        String walletAddress = replicate.getWalletAddress();

        if (replicate.getLastRelevantStatus().isEmpty()) {
            return Optional.empty();
        }

        boolean isInStatusContributed = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.CONTRIBUTED);
        boolean didReplicateStartRevealing = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.REVEALING);
        boolean didReplicateRevealOnChain = replicatesService.didReplicateRevealOnchain(chainTaskId, walletAddress);

        if (isInStatusContributed) {
            return Optional.of(TaskNotificationType.PLEASE_REVEAL);
        }

        if (didReplicateStartRevealing && !didReplicateRevealOnChain) {
            return Optional.of(TaskNotificationType.PLEASE_REVEAL);
        }

        if (didReplicateStartRevealing && didReplicateRevealOnChain) {
            ReplicateStatusDetails details = new ReplicateStatusDetails(blockNumber);
            replicatesService.updateReplicateStatus(chainTaskId, walletAddress, REVEALED, details);
            taskUpdateRequestManager.publishRequest(chainTaskId).join();
        }

        // we read the replicate from db to consider the changes added in the previous case
        Optional<Replicate> oReplicateWithLatestChanges = replicatesService.getReplicate(chainTaskId, walletAddress);

        if (oReplicateWithLatestChanges.isEmpty()) {
            return Optional.empty();
        }
        replicate = oReplicateWithLatestChanges.get();
        if (replicate.getLastRelevantStatus().isEmpty()) {
            return Optional.empty();
        }

        boolean didReplicateReveal = replicate.getLastRelevantStatus().get()
                .equals(ReplicateStatus.REVEALED);

        boolean wasReplicateRequestedToUpload = replicate.getLastRelevantStatus().get()
                .equals(ReplicateStatus.RESULT_UPLOAD_REQUESTED);

        if (didReplicateReveal) {
            return Optional.of(TaskNotificationType.PLEASE_WAIT);
        }

        if (wasReplicateRequestedToUpload) {
            return Optional.of(TaskNotificationType.PLEASE_UPLOAD);
        }

        return Optional.empty();
    }

    /**
     * RESULT_UPLOAD_REQUESTED          => TaskNotificationType.PLEASE_UPLOAD_RESULT
     * RESULT_UPLOADING + !done yet     => TaskNotificationType.PLEASE_UPLOAD_RESULT
     * RESULT_UPLOADING + done          => TaskNotificationType.PLEASE_WAIT
     * update to ReplicateStatus.RESULT_UPLOADED
     * RESULT_UPLOADED                  => TaskNotificationType.PLEASE_WAIT
     */

    private Optional<TaskNotificationType> recoverReplicateInResultUploadPhase(Task task, Replicate replicate) {
        String chainTaskId = task.getChainTaskId();
        String walletAddress = replicate.getWalletAddress();

        if (replicate.getLastRelevantStatus().isEmpty()) {
            return Optional.empty();
        }

        boolean wasReplicateRequestedToUpload = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.RESULT_UPLOAD_REQUESTED);
        boolean didReplicateStartUploading = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.RESULT_UPLOADING);
        boolean didReplicateUploadWithoutNotifying = replicatesService.isResultUploaded(task.getChainTaskId());
        boolean hasReplicateAlreadyUploaded = replicate.getLastRelevantStatus().get().equals(ReplicateStatus.RESULT_UPLOADED);

        if (wasReplicateRequestedToUpload) {
            return Optional.of(TaskNotificationType.PLEASE_UPLOAD);
        }

        if (didReplicateStartUploading && !didReplicateUploadWithoutNotifying) {
            return Optional.of(TaskNotificationType.PLEASE_UPLOAD);
        }

        if (didReplicateStartUploading && didReplicateUploadWithoutNotifying) {
            replicatesService.updateReplicateStatus(chainTaskId, walletAddress, RESULT_UPLOADED);

            taskUpdateRequestManager.publishRequest(chainTaskId);
            return Optional.of(TaskNotificationType.PLEASE_WAIT);
        }

        if (hasReplicateAlreadyUploaded) {
            return Optional.of(TaskNotificationType.PLEASE_WAIT);
        }

        return Optional.empty();
    }

    /**
     * REVEALED + task in COMPLETED status          => TaskNotificationType.PLEASE_COMPLETE
     * REVEALED + task not in COMPLETED status      => TaskNotificationType.PLEASE_WAIT
     * !REVEALED                                    => null
     */

    private Optional<TaskNotificationType> recoverReplicateIfRevealed(Replicate replicate) {
        // refresh task
        Optional<Task> oTask = taskService.getTaskByChainTaskId(replicate.getChainTaskId());
        if (oTask.isEmpty()) {
            return Optional.empty();
        }

        if (replicate.containsRevealedStatus()) {
            if (oTask.get().getCurrentStatus().equals(TaskStatus.COMPLETED)) {
                return Optional.of(TaskNotificationType.PLEASE_COMPLETE);
            }

            return Optional.of(TaskNotificationType.PLEASE_WAIT);
        }

        return Optional.empty();
    }

    private TaskAbortCause getTaskAbortCause(Task task) {
        switch (task.getCurrentStatus()) {
            case CONSENSUS_REACHED:
                return TaskAbortCause.CONSENSUS_REACHED;
            case CONTRIBUTION_TIMEOUT:
                return TaskAbortCause.CONTRIBUTION_TIMEOUT;
            default:
                return TaskAbortCause.UNKNOWN;
        }
    }
}
