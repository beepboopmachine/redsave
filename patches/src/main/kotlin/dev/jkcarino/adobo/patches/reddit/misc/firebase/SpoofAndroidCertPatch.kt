package dev.jkcarino.adobo.patches.reddit.misc.firebase

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.stringOption
import dev.jkcarino.adobo.patches.all.firebase.installations.baseSpoofAndroidCertPatch
import dev.jkcarino.adobo.patches.reddit.shared.COMPATIBILITY_REDDIT

val spoofCertificateHashPatch = patch(
    name = "Spoof Firebase certificate hash",
    description = "Spoofs the app's Firebase certificate hash to allow push notifications."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    val certificateHash by stringOption(
        key = "certificateHash",
        title = "Certificate hash",
        description = "The SHA-1 hash of the app's package certificate.",
        default = "8bca1dda8b2418e5300fc91ac43bbf211290792e"
    ) { hash ->
        val hexPattern = """^[0-9A-Fa-f]{40}$""".toRegex()
        !hash.isNullOrEmpty() && hash.matches(hexPattern)
    }

    dependsOn(
        baseSpoofAndroidCertPatch(
            certificateHash = { certificateHash ?: "8bca1dda8b2418e5300fc91ac43bbf211290792e" }
        )
    )
}
