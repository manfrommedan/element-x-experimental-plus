/*
 * Copyright 2026 manfrommedan (Element X+ phone-layer fork)
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.phonelayer.brand"

    buildTypes {
        register("nightly")
    }
}
