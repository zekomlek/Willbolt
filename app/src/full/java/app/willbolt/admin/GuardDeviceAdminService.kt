package app.willbolt.admin

import android.app.admin.DeviceAdminService

/**
 * Declared so Android treats the app as a device-policy controller (those are exempt from "hibernation", the automatic
 * removal of permissions from unused apps). Full edition only: the Play edition leaves Android's own privacy control alone
 * and asks the user to switch "Keep permissions when unused" off themselves (Settings > Setup).
 */
class GuardDeviceAdminService : DeviceAdminService()
