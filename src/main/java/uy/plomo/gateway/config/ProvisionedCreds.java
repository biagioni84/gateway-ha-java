package uy.plomo.gateway.config;

import lombok.Data;

/**
 * Credentials provisioned by AWS IoT Fleet Provisioning.
 * Loaded from provisioned.creds at startup.
 *
 * The file can be in EDN format (Clojure legacy):
 *   {:name "gw-xxx" :cert-pem "-----BEGIN..." :private-key "-----BEGIN..." :cert-id "abc"}
 * or in JSON format (new Java version):
 *   {"name":"gw-xxx","certPem":"-----BEGIN...","privateKey":"-----BEGIN...","certId":"abc"}
 */
@Data
public class ProvisionedCreds {
    private String name;
    private String certPem;
    private String privateKey;
    private String certId;
    private String serialNumber;

    public boolean isComplete() {
        return name != null && !name.isBlank()
                && certPem != null && !certPem.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
