package com.aliyun.autowonder.frontendproject;

import com.aliyun.autowonder.repo.RepoDO;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** Legacy probe entry is deliberately inert: the platform has no repository Git identity. */
@Component
public class GitRepoCheckout implements RepoCheckout {
    @Override
    public Checkout checkout(RepoDO repo, Path workDir) {
        throw new RepoCheckoutException("Repository probing retired; configure the PRD target manually");
    }
}
