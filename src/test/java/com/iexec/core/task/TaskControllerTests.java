/*
 * Copyright 2022 IEXEC BLOCKCHAIN TECH
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

package com.iexec.core.task;

import com.iexec.common.chain.eip712.EIP712Domain;
import com.iexec.common.chain.eip712.entity.Challenge;
import com.iexec.common.chain.eip712.entity.EIP712Challenge;
import com.iexec.common.replicate.ComputeLogs;
import com.iexec.common.task.TaskDescription;
import com.iexec.common.utils.CredentialsUtils;
import com.iexec.core.chain.IexecHubService;
import com.iexec.core.logs.TaskLogs;
import com.iexec.core.logs.TaskLogsService;
import com.iexec.core.replicate.Replicate;
import com.iexec.core.replicate.ReplicateModel;
import com.iexec.core.replicate.ReplicatesService;
import com.iexec.core.security.EIP712ChallengeService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Slf4j
class TaskControllerTests {

    private static final String LOGS_REQUESTER_TOKEN = "hash_signature_logsRequesterAddress";
    private static final String TASK_REQUESTER_TOKEN = "hash_signature_taskRequesterAddress";
    private static final String TASK_ID = "0xtask";
    private static final String WORKER_ADDRESS = "0xworker";

    private ECKeyPair ecKeyPair;
    private EIP712Challenge challenge;
    private EIP712Challenge badChallenge;
    private String requesterAddress;
    private String signature;

    @Mock private EIP712ChallengeService challengeService;
    @Mock private IexecHubService iexecHubService;
    @Mock private ReplicatesService replicatesService;
    @Mock private TaskService taskService;
    @Mock private TaskLogsService taskLogsService;
    @InjectMocks private TaskController taskController;

    @BeforeEach
    void setUp() throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        MockitoAnnotations.openMocks(this);
        EIP712Domain domain = new EIP712Domain();
        challenge = new EIP712Challenge(domain, Challenge.builder().challenge("challenge").build());
        badChallenge = new EIP712Challenge(domain, Challenge.builder().challenge("bad-challenge").build());
        ecKeyPair = Keys.createEcKeyPair();
        requesterAddress = CredentialsUtils.getAddress(String.format("0x%032x", ecKeyPair.getPrivateKey()));
        signature = challenge.signMessage(ecKeyPair);
    }

    //region utilities
    String generateWalletAddress() throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        ECKeyPair ecKeyPair = Keys.createEcKeyPair();
        BigInteger pk = ecKeyPair.getPrivateKey();
        return CredentialsUtils.getAddress(String.format("0x%032x", pk));
    }
    //endregion

    //region getChallenge
    @Test
    void shouldGetChallenge() {
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        ResponseEntity<EIP712Challenge> response = taskController.getChallenge(requesterAddress);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(challenge, response.getBody());
        verify(challengeService).getChallenge(requesterAddress);
    }
    //endregion

    //region getTask
    @Test
    void shouldGetTaskModel() {
        Task taskEntity = mock(Task.class);
        when(taskEntity.getChainTaskId()).thenReturn(TASK_ID);
        when(taskService.getTaskByChainTaskId(TASK_ID))
                .thenReturn(Optional.of(taskEntity));

        ResponseEntity<TaskModel> taskResponse = taskController.getTask(TASK_ID);
        assertTrue(taskResponse.getStatusCode().is2xxSuccessful());
        TaskModel task = taskResponse.getBody();
        assertNotNull(task);
        assertEquals(TASK_ID, task.getChainTaskId());
        assertNull(task.getReplicates());
    }

    @Test
    void shouldGetTaskModelWithReplicates() {
        Task taskEntity = mock(Task.class);
        when(taskEntity.getChainTaskId()).thenReturn(TASK_ID);
        when(taskService.getTaskByChainTaskId(TASK_ID))
                .thenReturn(Optional.of(taskEntity));
        when(replicatesService.hasReplicatesList(TASK_ID))
                .thenReturn(true);
        Replicate replicateEntity = new Replicate(WORKER_ADDRESS, TASK_ID);
        when(replicatesService.getReplicates(TASK_ID))
                .thenReturn(List.of(replicateEntity));

        ResponseEntity<TaskModel> taskResponse = taskController.getTask(TASK_ID);
        assertTrue(taskResponse.getStatusCode().is2xxSuccessful());
        TaskModel task = taskResponse.getBody();
        assertNotNull(task);
        assertEquals(TASK_ID, task.getChainTaskId());
        assertEquals(TASK_ID,
                task.getReplicates().get(0).getChainTaskId());
        assertEquals(WORKER_ADDRESS,
                task.getReplicates().get(0).getWalletAddress());
    }
    //endregion

    //region getTaskReplicate
    @Test
    void shouldGetReplicate() {
        Replicate replicateEntity = new Replicate(WORKER_ADDRESS, TASK_ID);
        when(replicatesService.getReplicate(TASK_ID, WORKER_ADDRESS))
                .thenReturn(Optional.of(replicateEntity));

        ResponseEntity<ReplicateModel> replicateResponse =
                taskController.getTaskReplicate(TASK_ID, WORKER_ADDRESS);
        assertTrue(replicateResponse.getStatusCode().is2xxSuccessful());
        ReplicateModel replicate = replicateResponse.getBody();
        assertNotNull(replicate);
        assertEquals(TASK_ID, replicate.getChainTaskId());
        assertEquals(WORKER_ADDRESS, replicate.getWalletAddress());
    }

    @Test
    void shouldNotGetReplicate() {
        when(replicatesService.getReplicate(TASK_ID, WORKER_ADDRESS))
                .thenReturn(Optional.empty());

        ResponseEntity<ReplicateModel> replicateResponse =
                taskController.getTaskReplicate(TASK_ID, WORKER_ADDRESS);
        assertTrue(replicateResponse.getStatusCode().is4xxClientError());
    }
    //endregion

    //region buildReplicateModel
    @Test
    void shouldBuildReplicateModel() {
        Replicate entity = mock(Replicate.class);
        when(entity.getChainTaskId()).thenReturn(TASK_ID);
        when(entity.getWalletAddress()).thenReturn(WORKER_ADDRESS);
        when(entity.isAppComputeLogsPresent()).thenReturn(false);

        ReplicateModel dto = taskController.buildReplicateModel(entity);
        assertEquals(TASK_ID, dto.getChainTaskId());
        assertTrue(dto.getSelf().endsWith("/tasks/0xtask/replicates/0xworker"));
        assertNull(dto.getAppLogs());
    }

    @Test
    void shouldBuildReplicateModelWithComputeLogs() {
        Replicate entity = mock(Replicate.class);
        when(entity.getChainTaskId()).thenReturn(TASK_ID);
        when(entity.getWalletAddress()).thenReturn(WORKER_ADDRESS);
        when(entity.isAppComputeLogsPresent()).thenReturn(true);

        ReplicateModel dto = taskController.buildReplicateModel(entity);
        assertEquals(TASK_ID, dto.getChainTaskId());
        assertTrue(dto.getSelf().endsWith("/tasks/0xtask/replicates/0xworker"));
        assertTrue(dto.getAppLogs().endsWith("/tasks/0xtask/replicates/0xworker/stdout"));
    }
    //endregion

    //region getTaskLogs
    @Test
    void shouldGetTaskLogsWhenAuthenticated() {
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        TaskLogs taskStdout = TaskLogs.builder().build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getTaskLogs(TASK_ID)).thenReturn(Optional.of(taskStdout));
        ResponseEntity<TaskLogs> response = taskController.getTaskLogs(TASK_ID, authorization);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetTaskLogsWithNotFoundStatus() {
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getTaskLogs(TASK_ID)).thenReturn(Optional.empty());
        ResponseEntity<TaskLogs> response = taskController.getTaskLogs(TASK_ID, authorization);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetTaskLogsWhenBadChallenge() {
        String authorization = String.join("_", badChallenge.getHash(), badChallenge.signMessage(ecKeyPair), requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getComputeLogs(TASK_ID, WORKER_ADDRESS)).thenReturn(Optional.empty());
        ResponseEntity<TaskLogs> response = taskController.getTaskLogs(TASK_ID, authorization);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetTaskLogsWhenInvalidAuthorization() {
        ResponseEntity<TaskLogs> response = taskController.getTaskLogs(TASK_ID, "");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(iexecHubService);
    }

    @Test
    void shouldFailToGetTaskLogsWhenNotTaskRequester()
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        String notRequesterAddress = generateWalletAddress();
        TaskDescription description = TaskDescription.builder()
                .requester(CredentialsUtils.getAddress(notRequesterAddress))
                .build();
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        ResponseEntity<TaskLogs> response = taskController.getTaskLogs(TASK_ID, LOGS_REQUESTER_TOKEN);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }
    //endregion

    //region getComputeLogs
    @Test
    void shouldGetComputeLogsWhenAuthenticated() {
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        ComputeLogs computeLogs = ComputeLogs.builder().build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getComputeLogs(TASK_ID, WORKER_ADDRESS)).thenReturn(Optional.of(computeLogs));
        ResponseEntity<ComputeLogs> response = taskController.getComputeLogs(TASK_ID, WORKER_ADDRESS, authorization);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetComputeLogsWithNotFoundStatus() {
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getComputeLogs(TASK_ID, WORKER_ADDRESS)).thenReturn(Optional.empty());
        ResponseEntity<ComputeLogs> response = taskController.getComputeLogs(TASK_ID, WORKER_ADDRESS, authorization);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetComputeLogsWhenBadChallenge() {
        String authorization = String.join("_", badChallenge.getHash(), badChallenge.signMessage(ecKeyPair), requesterAddress);
        TaskDescription description = TaskDescription.builder()
                .requester(requesterAddress)
                .build();
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        when(taskLogsService.getComputeLogs(TASK_ID, WORKER_ADDRESS)).thenReturn(Optional.empty());
        ResponseEntity<ComputeLogs> response = taskController.getComputeLogs(TASK_ID, WORKER_ADDRESS, authorization);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }

    @Test
    void shouldFailToGetComputeLogsWhenInvalidAuthorization() {
        ResponseEntity<ComputeLogs> response = taskController.getComputeLogs(TASK_ID, WORKER_ADDRESS, "");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(iexecHubService);
    }

    @Test
    void shouldFailToGetComputeLogsWhenNotTaskRequester()
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        String notRequesterAddress = generateWalletAddress();
        TaskDescription description = TaskDescription.builder()
                .requester(CredentialsUtils.getAddress(notRequesterAddress))
                .build();
        String authorization = String.join("_", challenge.getHash(), signature, requesterAddress);
        when(iexecHubService.getTaskDescription(TASK_ID)).thenReturn(description);
        when(challengeService.getChallenge(requesterAddress)).thenReturn(challenge);
        ResponseEntity<ComputeLogs> response = taskController.getComputeLogs(TASK_ID, WORKER_ADDRESS, LOGS_REQUESTER_TOKEN);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(iexecHubService).getTaskDescription(TASK_ID);
    }
    //endregion

}
