/*
 * Copyright 2026 manfrommedan (Element X+ phone-layer fork)
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Asset-only module that ships the patched Element Call bundle under
// `assets/element-call/`. Pulled in by the `plus` flavor only.
// Vanilla builds get the upstream Element Call WebView served from element.io.
plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.phonelayer.elementcallbundle"

    buildTypes {
        register("nightly")
    }
}
