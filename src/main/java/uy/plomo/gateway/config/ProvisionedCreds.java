package uy.plomo.gateway.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Credentials provisioned by AWS IoT Fleet Provisioning, or loaded by hand via the manual
 * provisioning UI (POST /api/v1/provisioning). Loaded from provisioned.creds (JSON) at startup:
 *   {"name":"gw-xxx","certPem":"-----BEGIN...","privateKey":"-----BEGIN...","certId":"abc"}
 *
 * iotEndpoint is optional — when absent, MqttService falls back to the aws.iot.endpoint property.
 */
@Data
public class ProvisionedCreds {
    private String name;
    private String certPem;
    private String privateKey;
    private String certId;
    private String serialNumber;
    private String iotEndpoint;

    @JsonIgnore
    public boolean isComplete() {
        return name != null && !name.isBlank()
                && certPem != null && !certPem.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
