package com.karr.key.protocol

/** A selector recovered from the original settings layouts for READ_SPECIFIC_SETTING. */
data class QtSpecificSettingOption(val selector: Int, val label: String)

val qtSpecificSettingOptions: List<QtSpecificSettingOption> = listOf(
    QtSpecificSettingOption(0x00, "Auto-lock with ignition"),
    QtSpecificSettingOption(0x01, "Auto-unlock with ignition"),
    QtSpecificSettingOption(0x02, "Passive arm locks doors"),
    QtSpecificSettingOption(0x03, "User arming"),
    QtSpecificSettingOption(0x04, "Lock / unlock pulse"),
    QtSpecificSettingOption(0x05, "Short pulse duration"),
    QtSpecificSettingOption(0x06, "Arm / disarm tones"),
    QtSpecificSettingOption(0x07, "Parking-light OEM trigger"),
    QtSpecificSettingOption(0x08, "Setting 8 (unmapped on DR1)"),
    QtSpecificSettingOption(0x09, "Setting 9 (unmapped on DR1)"),
    QtSpecificSettingOption(0x0A, "Confirmation chirp duration"),
    QtSpecificSettingOption(0x0B, "Factory-alarm output duration"),
    QtSpecificSettingOption(0x0C, "Arm / disarm trigger polarity"),
    QtSpecificSettingOption(0x0D, "User factory-alarm inhibit"),
    QtSpecificSettingOption(0x0E, "Alarm output"),
    QtSpecificSettingOption(0x0F, "Setting 15 (unmapped on DR1)"),
    QtSpecificSettingOption(0x10, "Unlock in user mode"),
    QtSpecificSettingOption(0x11, "Shock sensor sensitivity"),
    QtSpecificSettingOption(0x12, "BCA mode duration"),
    QtSpecificSettingOption(0x13, "Alarm color"),
    QtSpecificSettingOption(0x14, "Dealer arming"),
    QtSpecificSettingOption(0x15, "Auto-lock on Bluetooth disconnect"),
)
