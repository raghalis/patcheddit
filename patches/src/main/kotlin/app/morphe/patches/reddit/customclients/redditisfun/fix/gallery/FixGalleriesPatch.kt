/*
 * Copyright 2026 wchill.
 *
 * See the included NOTICE file for GPLv3 section 7 terms.
 */

package app.morphe.patches.reddit.customclients.redditisfun.fix.gallery

import app.morphe.patches.reddit.customclients.AppCompatibility
import app.morphe.patches.reddit.customclients.ExtensionPatches
import app.morphe.patches.reddit.customclients.fixGalleriesPatch

@Suppress("unused")
val fixGalleries = fixGalleriesPatch(
    extensionPatch = ExtensionPatches.RIF,
    compatible = AppCompatibility.RedditIsFun,
)
