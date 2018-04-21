package org.flaxo.github

import com.nhaarman.mockito_kotlin.mock
import org.flaxo.git.GitPayload
import org.flaxo.git.PullRequest
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import org.jetbrains.spek.subject.SubjectSpek
import org.kohsuke.github.GitHubBuilder
import java.io.Reader
import java.net.HttpURLConnection
import kotlin.test.assertTrue
import org.kohsuke.github.GitHub as KohsukeGithub

object GithubWebHookParserSpec : SubjectSpek<(Reader) -> GitPayload?>({

    val pullRequestAuthor = "pullRequestAuthor"
    val repositoryOwner = "repositoryOwner"
    val repositoryName = "repositoryName"
    val baseBranch = "baseBranch"
    val lastCommitSha = "lastCommitSha"

    val pullRequestCommitsBody =
            """
                [
                  {
                    "sha": "firstCommitSha"
                  },
                  {
                    "sha": "$lastCommitSha"
                  }
                ]
            """.trimIndent()

    val repositoryOwnerUserBody =
            """
                {
                  "login": "$repositoryOwner"
                }
            """.trimIndent()

    val webHookBody =
            """
                {
                    "action": "opened",
                    "pull_request": {
                        "user": {
                            "login": "$pullRequestAuthor"
                        },
                        "base": {
                            "ref": "$baseBranch"
                        }
                    },
                    "repository": {
                        "name": "$repositoryName",
                        "owner": {
                            "login": "$repositoryOwner"
                        }
                    }
                }
            """.trimIndent()

    val connectionMock = mock<HttpURLConnection> {
        on { requestMethod }.thenReturn("GET")
        on { responseCode }.thenReturn(200)
        on { inputStream }.thenReturn(
                pullRequestCommitsBody.byteInputStream(),
                repositoryOwnerUserBody.byteInputStream(),
                pullRequestCommitsBody.byteInputStream(),
                repositoryOwnerUserBody.byteInputStream()
        )
    }

    subject {
        { reader ->
            parseGithubEvent(reader, "pull_request",
                    GitHubBuilder
                            .fromEnvironment()
                            .withConnector { _ -> connectionMock }
                            .build()
            )
        }
    }

    describe("Github web hook parser") {

        on("parsing web hook") {
            val payload = subject(webHookBody.reader())
                    ?: throw GithubException("Github web hook wasn't parsed properly.")

            it("should get web hook type") {
                assertTrue { payload is PullRequest }
            }
        }

        on("parsing pull request web hook") {
            val payload = subject(webHookBody.reader())
                    as GithubPullRequest?
                    ?: throw GithubException("Github web hook wasn't parsed properly.")

            it("should determine pull request state") {
                assertTrue { payload.isOpened }
            }

            it("should determine pull request author") {
                assertTrue { payload.authorId == pullRequestAuthor }
            }

            it("should determine pull request repository owner") {
                assertTrue { payload.receiverId == repositoryOwner }
            }

            it("should determine pull request repository name") {
                assertTrue { payload.receiverRepositoryName == repositoryName }
            }

            it("should determine pull request base branch") {
                assertTrue { payload.baseBranch == baseBranch }
            }

            it("should determine pull request last commit sha") {
                assertTrue { payload.lastCommitSha == lastCommitSha }
            }
        }
    }
})