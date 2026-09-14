package paceline.testsupport

import paceline.device.ports.IndoorBikePowerControl

class FakeIndoorBikePowerControl : IndoorBikePowerControl {
    var requestControlCalls = 0
        private set
    val targetPowers = mutableListOf<Int>()
    var requestControlFailure: Exception? = null
    var targetPowerFailure: Exception? = null

    override fun requestControl() {
        requestControlCalls += 1
        requestControlFailure?.let { throw it }
    }

    override fun setTargetPower(powerWatts: Int) {
        targetPowerFailure?.let { throw it }
        targetPowers += powerWatts
    }
}
