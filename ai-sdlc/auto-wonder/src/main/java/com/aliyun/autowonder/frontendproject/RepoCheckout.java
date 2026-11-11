package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.repo.RepoDO;

import java.nio.file.Path;

/**
 * Produces a local read-only checkout of a repo at a resolved commit, for static probing.
 *
 * <p>Abstracted so the probe orchestrator can be unit-tested against a prepared directory without
 * a real {@code git} binary or network credentials. The production implementation must honour the
 * spec's probe constraints: no repository scripts, no dependency installation, no credential reads.
 */
public interface RepoCheckout {

    /**
     * Check out {@code repo} under {@code workDir} and report where the tree lives and which
     * commit was resolved.
     *
     * @throws RepoCheckoutException when the repo cannot be reached or checked out
     */
    Checkout checkout(RepoDO repo, Path workDir);

    /** The checked-out tree root, the branch probed and the resolved 40/64-hex commit SHA. */
    record Checkout(Path root, String branch, String commitSha) {}

    /** A checkout failure carrying a user-presentable, already-truncated reason. */
    class RepoCheckoutException extends RuntimeException {
        public RepoCheckoutException(String message) {
            super(message);
        }

        public RepoCheckoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
