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

import com.iexec.core.detector.Detector;
import com.iexec.core.task.Task;
import com.iexec.core.task.TaskExecutorEngine;
import com.iexec.core.task.TaskService;
import com.iexec.core.task.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class UnstartedTxDetector implements Detector {

    private TaskService taskService;
    private TaskExecutorEngine taskExecutorEngine;

    public UnstartedTxDetector(TaskService taskService,
                               TaskExecutorEngine taskExecutorEngine) {
        this.taskService = taskService;
        this.taskExecutorEngine = taskExecutorEngine;
    }

    @Scheduled(fixedRateString = "${cron.detector.unstartedtx.period}")
    @Override
    public void detect() {
        //start finalize when needed
        List<Task> notYetFinalizingTasks = taskService.findByCurrentStatus(TaskStatus.RESULT_UPLOADED);
        for (Task task : notYetFinalizingTasks) {
            log.info("Detected confirmed missing update (task) [is:{}, should:{}, chainTaskId:{}]",
                    TaskStatus.RESULT_UPLOADED, TaskStatus.FINALIZING, task.getChainTaskId());
            taskExecutorEngine.updateTask(task.getChainTaskId());
        }

        //start initialize when needed
        List<Task> notYetInitializedTasks = taskService.getInitializableTasks();
        for (Task task : notYetInitializedTasks) {
            log.info("Detected confirmed missing update (task) [is:{}, should:{}, chainTaskId:{}]",
                    TaskStatus.RECEIVED, TaskStatus.INITIALIZING, task.getChainTaskId());
            taskExecutorEngine.updateTask(task.getChainTaskId());
        }
    }
}

