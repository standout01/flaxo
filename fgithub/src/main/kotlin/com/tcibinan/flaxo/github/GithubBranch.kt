package com.tcibinan.flaxo.github

import com.tcibinan.flaxo.core.env.BinaryEnvironmentFile
import com.tcibinan.flaxo.core.env.EnvironmentFile
import com.tcibinan.flaxo.git.Branch
import com.tcibinan.flaxo.git.Git
import com.tcibinan.flaxo.git.Repository

data class GithubBranch(private val name: String,
                        private val repository: Repository,
                        private val git: Git
) : Branch {

    override fun name() = name
    override fun repository() = repository

    override fun load(file: EnvironmentFile): Branch = also {
        when (file) {
            is BinaryEnvironmentFile
            -> git.load(repository, this@GithubBranch, file.name(), file.binaryContent())
            else -> git.load(repository, this@GithubBranch, file.name(), file.content())
        }
    }

    override fun createSubBranches(count: Int, prefix: String): Branch = also {
        (1..count).map { prefix + it }
                .forEach { git.createSubBranch(repository, this, it) }
    }

    override fun files(): List<EnvironmentFile> =
            git.files(repository.owner(), repository.name(), name)

}