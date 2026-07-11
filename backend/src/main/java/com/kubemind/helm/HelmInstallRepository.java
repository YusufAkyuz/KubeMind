package com.kubemind.helm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HelmInstallRepository extends JpaRepository<HelmInstall, Long> {
    Optional<HelmInstall> findByClusterIdAndNamespaceAndReleaseName(long clusterId, String namespace, String releaseName);
}
