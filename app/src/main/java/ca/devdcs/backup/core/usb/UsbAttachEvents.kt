package ca.devdcs.backup.core.usb

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges USB_DEVICE_ATTACHED intents (received by MainActivity) to whoever is interested: the
 * OTG storage handler in and any visible screen that wants to offer the drive as a
 * destination. Replays the latest attach so a screen that appears after the intent still sees it.
 */
@Singleton
class UsbAttachEvents @Inject constructor() {

    private val _events = MutableSharedFlow<UsbDevice>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<UsbDevice> = _events.asSharedFlow()

    fun onAttached(device: UsbDevice) {
        _events.tryEmit(device)
    }
}
