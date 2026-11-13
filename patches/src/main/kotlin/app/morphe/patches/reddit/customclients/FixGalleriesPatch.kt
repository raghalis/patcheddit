/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 section 7 terms.
 */

package app.morphe.patches.reddit.customclients

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patches.all.misc.PatchCategory

@JvmOverloads
fun fixGalleriesPatch(
    extensionPatch: Patch<*>,
    compatible: Array<Compatibility>,
    default: Boolean = true,
) = morpheBytecodePatch(
    name = "Fix galleries",
    description = "Fixes Reddit image galleries failing to load because Reddit marks gallery images as failed.",
    default = default,
    extensionPatch = extensionPatch,
    category = PatchCategory.MEDIA,
) { setting ->
    compatibleWith(*compatible)
    dependsOn(extensionPatch)

    execute {
        setting.returnEnabled(Fingerprint(
            definingClass =
                "Lapp/morphe/extension/shared/fixes/gallery/GalleryMetadataResponsePatch;",
            name = "isPatchIncluded",
        ).method)
    }
}
