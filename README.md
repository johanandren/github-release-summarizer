# github-changelog-summarizer

## What the sample is/does

An Akka service that showcases Agentic AI

The agent keeps track of and creates an improved summaries, every time there is a release from set up GitHub repositories.

## Design overview

When a repository is added to the service, it creates an event sourced entity representing the repository. A Timed Action 
is set up to poll the GitHub release notes periodically, if it detects a latest release that it has not seen before, 
a summarization session is started using the Anthropic Claude LLM with tools, allowing Claude to request more details 
for issue and pull request ids mentioned in the release notes.

Once the LLM is done creating a summary, the summary is stored for in the repository entity. A consumer listens for summaries
and can act by publishing the summary somewhere (currently it logs it).

The interactions with Anthropic Claude is using the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java).

## Running the sample

To interact with the Anthropic LLM the environment variables ANTHROPIC_API_KEY and or ANTHROPIC_AUTH_TOKEN must be set
to tokens gotten from the [Anthropic console](https://console.anthropic.com/)

The GitHub API interactions are done anonymously by default, but a default API key can be defined using environment variable
`GITHUB_API_TOKEN` or config `github-api-token` in `application.conf`. Specific API keys can also be specified when a GitHub repository
is added, to use for interactions for that repository.


### Interacting with the sample

Set up a repository for tracking, this will trigger a summary pretty quickly:

A GitHub API access token for the specific repository can be provided through the body JSON field "gitHubApiToken", however
note that providing a key that allows for access to a private repository will pass potentially private information from
the release notes and issues of that project to Anthropic Claude.

```shell
curl http://localhost:9000/repo/akka/akka \
  --header "Content-Type: application/json" \
  -XPOST \
  --data '{}'
```

Fetch generated and stored summaries for a given project
```shell 
curl http://localhost:9000/repo/akka/akka/summaries
```

Trigger a one-off summary of the latest release (can be run without any prior calls). This is useful for playing around
with changes to the prompt:

```shell
curl http://localhost:9000/testing/repo/akka/akka-sdk/summarize-latest \
  -XPOST
```