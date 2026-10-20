package com.example.romdumper

/**
 * Best-effort helper for writing an already-extracted partition image back
 * to the device's own matching partition, using root ("su"). This only
 * works on a rooted device and only ever targets the device's own
 * block-device-by-name nodes — it does not touch, bypass, or unlock
 * anything; it is the same mechanism recovery/flashing tools use.
 */
object FlashHelper {

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val exited = p.waitFor()
        exited == 0
    } catch (e: Exception) {
        false
    }

    /** Returns true/false success plus a human-readable message. */
    fun flashPartition(imagePath: String, partitionName: String): Pair<Boolean, String> {
        return try {
            val slotProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop ro.boot.slot_suffix"))
            val slotSuffix = slotProc.inputStream.bufferedReader().readText().trim()
            slotProc.waitFor()

            val target = partitionName + slotSuffix
            val cmd = "dd if='$imagePath' of=/dev/block/by-name/$target bs=4194304"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val errorOutput = proc.errorStream.bufferedReader().readText()
            val exitCode = proc.waitFor()

            if (exitCode == 0) {
                true to "Uğurla yazıldı: /dev/block/by-name/$target"
            } else {
                false to "dd xətası (kod $exitCode): $errorOutput"
            }
        } catch (e: Exception) {
            false to "Root tapılmadı və ya icra xətası: ${e.message}"
        }
    }
}
