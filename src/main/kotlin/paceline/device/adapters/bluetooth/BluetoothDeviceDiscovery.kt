package paceline.device.adapters.bluetooth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.gatt.cyclingpower.CyclingPowerUuid
import paceline.device.adapters.gatt.cyclingspeedcadence.CyclingSpeedCadenceUuid
import paceline.device.adapters.gatt.ftms.FtmsUuid
import paceline.device.adapters.gatt.heartrate.HeartRateUuid
import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DiscoveryFailureCode
import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.DeviceDiscoveryResult

@Component
class BluetoothDeviceDiscovery(
    private val bluetooth: BluetoothAccess,
) : BluetoothDiscovery {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discover(): DeviceDiscoveryResult = discover { }

    override fun discover(onCandidate: (DeviceDiscoveryCandidate) -> Unit): DeviceDiscoveryResult =
        try {
            val candidates = linkedMapOf<String, DeviceDiscoveryCandidate>()
            val serviceUuids =
                setOf(
                    FtmsUuid.FITNESS_MACHINE_SERVICE,
                    CyclingPowerUuid.CYCLING_POWER_SERVICE,
                    CyclingSpeedCadenceUuid.CYCLING_SPEED_CADENCE_SERVICE,
                    HeartRateUuid.HEART_RATE_SERVICE,
                )
            bluetooth
                .discover(serviceUuids) { candidate ->
                    val mapped = toDeviceCandidate(candidate)
                    val previous = candidates.put(mapped.endpoint.toString(), mapped)
                    if (previous != mapped) {
                        onCandidate(mapped)
                    }
                }.map(::toDeviceCandidate)
                .forEach { candidate ->
                    val previous = candidates.put(candidate.endpoint.toString(), candidate)
                    if (previous != candidate) {
                        onCandidate(candidate)
                    }
                }
            val discovered = candidates.values.toList()
            if (discovered.isEmpty()) {
                logger.info("Bluetooth LE discovery found no matching device advertisements")
                DeviceDiscoveryResult.NotFound
            } else {
                DeviceDiscoveryResult.Found(discovered).also {
                    logger.info("Found {} device advertisement(s) via Bluetooth LE", discovered.size)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Bluetooth LE discovery failed", exception)
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "Bluetooth discovery failed",
            )
        }

    private fun toDeviceCandidate(candidate: BluetoothDeviceCandidate): DeviceDiscoveryCandidate =
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
