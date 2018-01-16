package org.entur.ukur.setup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class UkurConfiguration {

    @Value("${rutebanken.kubernetes.url:}")
    private String kubernetesUrl;

    @Value("${rutebanken.kubernetes.enabled:true}")
    private boolean kubernetesEnabled;

    @Value("${rutebanken.kubernetes.namespace:default}")
    private String namespace;

    @Value("${rutebanken.hazelcast.management.url:}")
    private String hazelcastManagementUrl;

    @Value("${ukur.camel.anshar.et.url}")
    private String etURL;

    @Value("${ukur.camel.anshar.sx.url}")
    private String sxURL;

    public String getHazelcastManagementUrl() {
        return hazelcastManagementUrl;
    }

    public String getKubernetesUrl() {
        return kubernetesUrl;
    }

    public boolean isKubernetesEnabled() {
        return kubernetesEnabled;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getAnsharETCamelUrl(UUID uuid) {
        return etURL + "?requestorId=" + uuid;
    }

    public String getAnsharSXCamelUrl(UUID uuid) {
        return sxURL + "?requestorId=" + uuid;
    }
}