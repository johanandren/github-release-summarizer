package summarizer.application;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import com.anthropic.client.AnthropicClient;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import summarizer.domain.ReleaseSummary;
import summarizer.domain.RepositoryIdentifier;
import summarizer.domain.SummarizerSession;
import summarizer.integration.GitHubApiClient;

import java.time.Duration;
import java.time.Instant;

/**
 * Timed action that checks for a new release, runs summarization if there is a new release, reschedules itself
 * for the next check.
 */
@ComponentId("check-for-release")
public final class CheckForRelease extends TimedAction {

  private final Logger logger = LoggerFactory.getLogger(CheckForRelease.class);

  private final ComponentClient componentClient;
  private final GitHubApiClient gitHubApiClient;
  private final AnthropicClient anthropicClient;
  private final TimerScheduler timerScheduler;
  private final Duration checkInterval;

  public CheckForRelease(ComponentClient componentClient, GitHubApiClient gitHubApiClient, AnthropicClient anthropicClient, TimerScheduler timerScheduler, Config config) {
    this.componentClient = componentClient;
    this.gitHubApiClient = gitHubApiClient;
    this.anthropicClient = anthropicClient;
    this.timerScheduler = timerScheduler;
    this.checkInterval = config.getDuration("new-release-check-interval");
  }

  public Effect checkForNewRelease(RepositoryIdentifier repositoryIdentifier) {
    logger.info("Checking for new release [{}]", repositoryIdentifier);

    var latestSeenRelease = componentClient.forEventSourcedEntity(GitHubRepositoryEntity.entityIdFor(repositoryIdentifier))
        .method(GitHubRepositoryEntity::getLatestSeenRelease)
        .invoke();

    var authorizedGitHubApiClient =
        latestSeenRelease.gitHubApiToken().map(gitHubApiClient::withApiToken).orElse(gitHubApiClient);

    var latestReleaseFromGitHub = authorizedGitHubApiClient.getLatestRelease(repositoryIdentifier.owner(), repositoryIdentifier.repo());
    if (latestSeenRelease.id().isEmpty() || latestSeenRelease.id().get() < latestReleaseFromGitHub.id()) {
      logger.info("Found new release for [{}], starting summarization", repositoryIdentifier);
      var summarizer = new SummarizerSession(authorizedGitHubApiClient, anthropicClient, repositoryIdentifier, latestReleaseFromGitHub);
      var summary = summarizer.summarize();

      componentClient.forEventSourcedEntity(GitHubRepositoryEntity.entityIdFor(repositoryIdentifier))
          .method(GitHubRepositoryEntity::addSummary)
          .invoke(new ReleaseSummary(summary.releaseName(), summary.gitHubReleaseId(), Instant.now(), summary.summaryText()));
    } else {
      logger.debug("No new release found for [{}]", repositoryIdentifier);
    }

    logger.debug("Scheduling next release check [{}]", Instant.now().plus(checkInterval));
    timerScheduler.startSingleTimer(repositoryIdentifier.toString(),
        checkInterval,
        componentClient.forTimedAction()
            .method(CheckForRelease::checkForNewRelease)
            .deferred(repositoryIdentifier)
    );
    return effects().done();
  }
}