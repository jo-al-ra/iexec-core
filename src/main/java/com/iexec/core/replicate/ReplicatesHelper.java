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

package com.iexec.core.replicate;

import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.replicate.ReplicateStatusUpdate;

import java.util.*;
import java.util.stream.Collectors;

import static com.iexec.common.replicate.ReplicateStatus.*;

public class ReplicatesHelper {
    private ReplicatesHelper() {

    }

    /**
     * Computes the number of replicates in the {@link ReplicateStatus#CONTRIBUTED} status
     * that have the right contribution hash.
     * <p>
     * Note this method won't retrieve the replicates list, so it could be static.
     * For test purposes - i.e. mocking -, it is not.
     *
     * @param replicates       List of replicates on which the calculation should be base.
     * @param contributionHash Valid hash of the final result.
     * @return Number of winners who had contributed with the right hash.
     */
    public static int getNbValidContributedWinners(List<Replicate> replicates, String contributionHash) {
        int nbValidWinners = 0;
        for (Replicate replicate : replicates) {
            Optional<ReplicateStatus> oStatus = replicate.getLastRelevantStatus();
            if (oStatus.isPresent() && oStatus.get().equals(CONTRIBUTED)
                    && contributionHash.equals(replicate.getContributionHash())) {
                nbValidWinners++;
            }
        }
        return nbValidWinners;
    }

    public static int getNbReplicatesWithCurrentStatus(List<Replicate> replicates, ReplicateStatus... listStatus) {
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

    public static int getNbReplicatesWithLastRelevantStatus(List<Replicate> replicates, ReplicateStatus... listStatus) {
        int nbReplicates = 0;
        for (Replicate replicate : replicates) {
            for (ReplicateStatus status : listStatus) {
                if (Objects.equals(replicate.getLastRelevantStatus().orElse(null), status)) {
                    nbReplicates++;
                }
            }
        }
        return nbReplicates;
    }

    public static int getNbReplicatesContainingStatus(List<Replicate> replicates, ReplicateStatus... listStatus) {
        Set<String> addressReplicates = new HashSet<>();
        for (Replicate replicate : replicates) {
            List<ReplicateStatus> listReplicateStatus = replicate.getStatusUpdateList().stream()
                    .map(ReplicateStatusUpdate::getStatus)
                    .collect(Collectors.toList());
            for (ReplicateStatus status : listStatus) {
                if (listReplicateStatus.contains(status)) {
                    addressReplicates.add(replicate.getWalletAddress());
                }
            }
        }
        return addressReplicates.size();
    }

    static boolean isStatusBeforeWorkerLostEqualsTo(Replicate replicate, ReplicateStatus status) {
        int size = replicate.getStatusUpdateList().size();
        return size >= 2
                && replicate.getStatusUpdateList().get(size - 1).getStatus().equals(WORKER_LOST)
                && replicate.getStatusUpdateList().get(size - 2).getStatus().equals(status);
    }

    public static Optional<Replicate> getRandomReplicateWithRevealStatus(List<Replicate> replicates) {
        final ArrayList<Replicate> clonedReplicates = new ArrayList<>(replicates);
        Collections.shuffle(clonedReplicates);

        for (Replicate replicate : clonedReplicates) {
            if (replicate.getCurrentStatus().equals(REVEALED)) {
                return Optional.of(replicate);
            }
        }

        return Optional.empty();
    }

    public static Optional<Replicate> getReplicateWithResultUploadedStatus(List<Replicate> replicates) {
        for (Replicate replicate : replicates) {

            boolean isStatusResultUploaded = replicate.getCurrentStatus().equals(RESULT_UPLOADED);
            boolean isStatusResultUploadedBeforeWorkerLost = isStatusBeforeWorkerLostEqualsTo(replicate, RESULT_UPLOADED);

            if (isStatusResultUploaded || isStatusResultUploadedBeforeWorkerLost) {
                return Optional.of(replicate);
            }
        }

        return Optional.empty();
    }

    public static boolean hasWorkerAlreadyParticipated(ReplicatesList replicatesList, String walletAddress) {
        return replicatesList.getReplicateOfWorker(walletAddress).isPresent();
    }
}
