package com.iexec.core.replicate;

import com.iexec.common.replicate.ReplicateStatusModifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.iexec.common.replicate.ReplicateStatus.CONTRIBUTED;
import static com.iexec.common.utils.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReplicatesHelperTests {
    // FIXME: add tests

    // region getNbValidContributedWinners
    @Test
    void shouldGetOneContributionWinnerAmongTwoContributors() {
        String contributionHash = "hash";
        String badContributionHash = "badHash";
        Replicate replicate1 = new Replicate(WALLET_WORKER_1, CHAIN_TASK_ID);
        replicate1.updateStatus(CONTRIBUTED, ReplicateStatusModifier.WORKER);
        replicate1.setContributionHash(contributionHash);
        Replicate replicate2 = new Replicate(WALLET_WORKER_2, CHAIN_TASK_ID);
        replicate2.updateStatus(CONTRIBUTED, ReplicateStatusModifier.WORKER);
        replicate2.setContributionHash(badContributionHash);
        ReplicatesList replicatesList = new ReplicatesList(CHAIN_TASK_ID,
                Arrays.asList(replicate1, replicate2));

        assertThat(ReplicatesHelper.getNbValidContributedWinners(
                replicatesList.getReplicates(),
                contributionHash
        )).isOne();
    }
    // endregion
}