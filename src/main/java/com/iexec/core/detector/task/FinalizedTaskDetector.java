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

package com.iexec.core.detector.task;

import com.iexec.common.chain.ChainTask;
import com.iexec.common.chain.ChainTaskStatus;
import com.iexec.core.chain.IexecHubService;
import com.iexec.core.detector.Detector;
import com.iexec.core.task.Task;
import com.iexec.core.task.TaskService;
import com.iexec.core.task.TaskStatus;
import com.iexec.core.task.update.TaskUpdateRequestManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class FinalizedTaskDetector implements Detector {

    private final TaskService taskService;
    private final TaskUpdateRequestManager taskUpdateRequestManager;
    private final IexecHubService iexecHubService;

    public FinalizedTaskDetector(TaskService taskService,
                                 TaskUpdateRequestManager taskUpdateRequestManager,
                                 IexecHubService iexecHubService) {
        this.taskService = taskService;
        this.taskUpdateRequestManager = taskUpdateRequestManager;
        this.iexecHubService = iexecHubService;
    }

    /**
     * Detector to detect tasks that are finalizing but are not finalized yet.
     */
    @Scheduled(fixedRateString = "#{@cronConfiguration.getFinalize()}")
    @Override
    public void detect() {
        log.debug("Trying to detect finalized tasks");
        for (Task task : taskService.findByCurrentStatus(TaskStatus.FINALIZING)) {
            Optional<ChainTask> chainTask = iexecHubService.getChainTask(task.getChainTaskId());
            if (chainTask.isPresent() && chainTask.get().getStatus().equals(ChainTaskStatus.COMPLETED)) {
                log.info("Detected confirmed missing update (task) [is:{}, should:{}, taskId:{}]",
                        task.getCurrentStatus(), TaskStatus.FINALIZED, task.getChainTaskId());
                taskUpdateRequestManager.publishRequest(task.getChainTaskId());
            }
        }
    }
}

