package com.aliyun.autowonder.configuration;

import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Optional;

@Configuration
public class SigarConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SigarConfiguration.class);

    private final SigarNativeLoader loader;
    private boolean enabled = true;

    public SigarConfiguration() {
        this(new SigarNativeLoader());
    }

    SigarConfiguration(SigarNativeLoader loader) {
        this.loader = loader;
    }

    @Value("${autowonder.metrics.sigar.enabled:true}")
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Optional<Sigar> createSigar() {
        return createSigar(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    Optional<Sigar> createSigar(String osName, String osArch) {
        if (!enabled || !SigarNativeLoader.isSupported(osName, osArch)) {
            return Optional.empty();
        }
        try {
            System.setProperty("org.hyperic.sigar.path", loader.extract().toString());
            Sigar.load();
            Sigar sigar = new Sigar();
            sigar.getPid();
            return Optional.of(sigar);
        } catch (IOException | SigarException | UnsatisfiedLinkError | SecurityException e) {
            LOGGER.warn("SIGAR metrics unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
