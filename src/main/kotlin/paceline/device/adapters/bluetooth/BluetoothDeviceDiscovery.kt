package paceline.device.adapters.bluetooth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.gatt.ftms.FtmsUuid
import paceline.device.adapters.gatt.heartrate.HeartRateUuid
import paceline.device.domain.ConnectionFailureCode
import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.DeviceDiscoveryResult

@Component
class BluetoothDeviceDiscovery(
    private val bluetooth: BluetoothAccess,
) : BluetoothDiscovery {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discover(): DeviceDiscoveryResult =
        try {
            val candidates =
                bluetooth
                    .discover(
                        setOf(
                            FtmsUuid.FITNESS_MACHINE_SERVICE,
                            HeartRateUuid.HEART_RATE_SERVICE,
                        ),
                    ).map { candidate ->
                        DeviceDiscoveryCandidate(
                            name = candidate.name,
                            endpoint =
                                DeviceEndpoint.Bluetooth(
                                    address = candidate.address,
                                    adapterAddress = candidate.adapterAddress,
                                ),
                            metadata =
                                buildMap {
                                    put("transport", "bluetooth")
                                    put("bluetooth-address", candidate.address)
                                    put("bluetooth-adapter", candidate.adapterAddress)
                                    candidate.rssi?.let { put("rssi", it.toString()) }
                                },
                        )
                    }
            if (candidates.isEmpty()) {
                DeviceDiscoveryResult.NotFound
            } else {
                DeviceDiscoveryResult.Found(candidates).also {
                    logger.info("Found {} device advertisement(s) via Bluetooth LE", candidates.size)
                }
            }
        } catch (exception: Exception) {
            DeviceDiscoveryResult.Failed(
                code = ConnectionFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "Bluetooth discovery failed",
            )
        }
}
