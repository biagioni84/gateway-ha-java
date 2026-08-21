package uy.plomo.gateway.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByNode(String node);

    Optional<Device> findByIeeeAddr(String ieeeAddr);

    List<Device> findByProtocol(String protocol);
}
