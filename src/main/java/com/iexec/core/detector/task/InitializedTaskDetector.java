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
import com.iexec.core.task.TaskExecutorEngine;
import com.iexec.core.task.TaskService;
import com.iexec.core.task.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class InitializedTaskDetector implements Detector {

    private TaskService taskService;
    private TaskExecutorEngine taskExecutorEngine;
    private IexecHubService iexecHubService;

    public InitializedTaskDetector(TaskService taskService,
                                   TaskExecutorEngine taskExecutorEngine,
                                   IexecHubService iexecHubService) {
        this.taskService = taskService;
        this.taskExecutorEngine = taskExecutorEngine;
        this.iexecHubService = iexecHubService;
    }

    /**
     * Detector to detect tasks that are initializing but are not initialized yet.
     */
    @Scheduled(fixedRateString = "${cron.detector.task.initialized.unnotified.period}")
    @Override
    public void detect() {
        log.debug("Trying to detect initialized tasks");
        for (Task task : taskService.findByCurrentStatus(TaskStatus.INITIALIZING)) {
            Optional<ChainTask> chainTask = iexecHubService.getChainTask(task.getChainTaskId());
            if (chainTask.isPresent() && !chainTask.get().getStatus().equals(ChainTaskStatus.UNSET)) {
                log.info("Detected confirmed missing update (task) [is:{}, should:{}, taskId:{}]",
                        TaskStatus.INITIALIZING, TaskStatus.INITIALIZED, task.getChainTaskId());
                taskExecutorEngine.updateTask(task.getChainTaskId());
            }
        }
    }
}
