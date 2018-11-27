package com.iexec.core.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.iexec.common.dapp.DappType;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.core.replicate.Replicate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskModel {

    @Id
    private String id;

    @Version
    private Long version;

    private String chainTaskId;
    private DappType dappType;
    private String dappName;
    private String commandLine;
    private TaskStatus currentStatus;
    private List<TaskStatusChange> dateStatusList;
    private List<Replicate> replicates;
    private int trust;
    private String uploadingWorkerWalletAddress;
    private String consensus;

    public TaskModel(String dappName, String commandLine, int trust) {
        this.dappType = DappType.DOCKER;
        this.dappName = dappName;
        this.commandLine = commandLine;
        this.trust = trust;
        this.dateStatusList = new ArrayList<>();
        this.dateStatusList.add(new TaskStatusChange(TaskStatus.CREATED));
        this.currentStatus = TaskStatus.CREATED;
        this.replicates = new ArrayList<>();
    }

    public TaskModel(String dappName, String commandLine, int trust, String chainTaskId) {
        this(dappName, commandLine, trust);
        this.chainTaskId = chainTaskId;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setChainTaskId(String chainTaskId) {
        this.chainTaskId = chainTaskId;
    }

    public void setDateStatusList(List<TaskStatusChange> dateStatusList) {
        this.dateStatusList = dateStatusList;
    }

    public void setCommandLine(String commandLine) {
        this.commandLine = commandLine;
    }

    public void setReplicates(List<Replicate> replicates) {
        this.replicates = replicates;
    }

    public void setTrust(int trust) {
        this.trust = trust;
    }

    public void setUploadingWorkerWalletAddress(String uploadingWorkerWalletAddress) {
        this.uploadingWorkerWalletAddress = uploadingWorkerWalletAddress;
    }

    public void setCurrentStatus(TaskStatus status) {
        this.currentStatus = status;
    }

    public void changeStatus(TaskStatus status) {
        setCurrentStatus(status);
        this.getDateStatusList().add(new TaskStatusChange(status));
    }

    public boolean createNewReplicate(String walletAddress) {
        return replicates.add(new Replicate(walletAddress, chainTaskId));
    }

    public Optional<Replicate> getReplicate(String walletAddress) {
        for (Replicate replicate : replicates) {
            if (replicate.getWalletAddress().equals(walletAddress)) {
                return Optional.of(replicate);
            }
        }
        return Optional.empty();
    }

    public boolean needMoreReplicates() {
        int nbValidReplicates = 0;
        for (Replicate replicate : getReplicates()) {
            if (!(replicate.getCurrentStatus().equals(ReplicateStatus.ERROR)
                    || replicate.getCurrentStatus().equals(ReplicateStatus.WORKER_LOST))) {
                nbValidReplicates++;
            }
        }
        return nbValidReplicates < trust;
    }

    public boolean hasWorkerAlreadyContributed(String walletAddress) {
        for (Replicate replicate : replicates) {
            if (replicate.getWalletAddress().equals(walletAddress)) {
                return true;
            }
        }
        return false;
    }


    @JsonIgnore
    public TaskStatusChange getLatestStatusChange() {
        return this.getDateStatusList().get(this.getDateStatusList().size() - 1);
    }

    public int getNbReplicatesWithStatus(ReplicateStatus status) {
        int nbReplicates = 0;
        for (Replicate replicate : replicates) {
            if (replicate.getCurrentStatus().equals(status)) {
                nbReplicates++;
            }
        }
        return nbReplicates;
    }

    public int getNbReplicatesStatusEqualTo(ReplicateStatus... listStatus) {
        int nbReplicates = 0;
        for (Replicate replicate : replicates) {
            for (ReplicateStatus status : listStatus) {
                if (replicate.getCurrentStatus().equals(status)) {
                    nbReplicates++;
                }
            }
        }
        return nbReplicates;
    }

    public void setConsensus(String consensus) {
        this.consensus = consensus;
    }
}