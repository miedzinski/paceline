package paceline.testsupport

import paceline.device.ports.IndoorBikePowerControl

class FakeIndoorBikePowerControl : IndoorBikePowerControl {
    var requestControlCalls = 0
        private set
    var freeRideCalls = 0
        private set
    val targetPowers = mutableListOf<Int>()
    val targetPowerAttempts = mutableListOf<Int>()
    var requestControlFailure: Exception? = null
    var freeRideFailure: Exception? = null
    var targetPowerFailure: Exception? = null

    override fun requestControl() {
        requestControlCalls += 1
        requestControlFailure?.let { throw it }
    }

    override fun setTargetPower(powerWatts: Int) {
        targetPowerAttempts += powerWatts
        targetPowerFailure?.let { throw it }
        targetPowers += powerWatts
    }

    override fun setFreeRide() {
        freeRideCalls += 1
        freeRideFailure?.let { throw it }
    }
}
