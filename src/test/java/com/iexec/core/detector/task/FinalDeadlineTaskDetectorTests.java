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

import com.iexec.core.task.Task;
import com.iexec.core.task.TaskService;
import com.iexec.core.task.TaskStatus;
import com.iexec.core.task.update.TaskUpdateRequestManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class FinalDeadlineTaskDetectorTests {

    private final static String CHAIN_TASK_ID = "chainTaskId";

    @Mock
    private TaskService taskService;

    @Mock private
    TaskUpdateRequestManager taskUpdateRequestManager;

    @InjectMocks
    private FinalDeadlineTaskDetector finalDeadlineTaskDetector;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
    }

    private Task getTask() {
        Task task = new Task("", "", 0);
        task.changeStatus(TaskStatus.RUNNING);
        return task;
    }

    @Test
    void shouldDetectTaskAfterFinalDeadline() {
        Task task = getTask();
        task.setFinalDeadline(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

        when(taskService.getTasksWhereFinalDeadlineIsPossible()).thenReturn(Collections.singletonList(task));

        finalDeadlineTaskDetector.detect();

        Mockito.verify(taskUpdateRequestManager, Mockito.times(1))
                .publishRequest(any());
    }

    @Test
    void shouldDetectTaskBeforeFinalDeadline() {
        Task task = getTask();
        task.setFinalDeadline(Date.from(Instant.now().plus(1, ChronoUnit.MINUTES)));

        when(taskService.getTasksWhereFinalDeadlineIsPossible()).thenReturn(Collections.singletonList(task));

        finalDeadlineTaskDetector.detect();

        Mockito.verify(taskUpdateRequestManager, never())
                .publishRequest(any());
    }
}
