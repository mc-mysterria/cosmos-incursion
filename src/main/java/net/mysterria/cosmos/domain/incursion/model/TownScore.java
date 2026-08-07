package net.mysterria.cosmos.domain.incursion.model;

/**
 * A town's ranked contribution result for a single incursion event.
 *
 * @param rank      1-based placement among all towns that scored (ties broken by contested
 *                  seconds, then capture count — see RewardDistributor)
 * @param share     this town's score as a fraction of the total score across all towns
 *                  (before nation amplification), used to split the resource pool
 * @param qualified whether this town cleared {@code rewards.contribution.min-score-share} and
 *                  therefore actually receives a reward
 * @param nationId  0 if the town belongs to no nation (or the backend doesn't support nations)
 */
public record TownScore(int townId, String townName, double score, double share, int rank,
                        boolean qualified, int nationId, String nationName) {
}
