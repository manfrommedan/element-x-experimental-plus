/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import config.BuildTimeConfig
import extension.buildConfigFieldStr
import extension.readLocalProperty
import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
}

android {
    namespace = "io.element.android.features.location.api"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Legacy MapTiler fields kept for compatibility with upstream code paths
        // that still reference them. Runtime no longer uses MapTiler.
        buildConfigFieldStr(
            name = "MAPTILER_BASE_URL",
            value = BuildTimeConfig.SERVICES_MAPTILER_BASE_URL ?: "https://api.maptiler.com/maps"
        )
        buildConfigFieldStr(
            name = "MAPTILER_API_KEY",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.SERVICES_MAPTILER_APIKEY
            } else {
                System.getenv("ELEMENT_ANDROID_MAPTILER_API_KEY")
                    ?: readLocalProperty("services.maptiler.apikey")
            }
                ?: ""
        )
        buildConfigFieldStr(
            name = "MAPTILER_LIGHT_MAP_ID",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.SERVICES_MAPTILER_LIGHT_MAPID
            } else {
                System.getenv("ELEMENT_ANDROID_MAPTILER_LIGHT_MAP_ID")
                    ?: readLocalProperty("services.maptiler.lightMapId")
            }
                ?: "basic-v2"
        )
        buildConfigFieldStr(
            name = "MAPTILER_DARK_MAP_ID",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.SERVICES_MAPTILER_DARK_MAPID
            } else {
                System.getenv("ELEMENT_ANDROID_MAPTILER_DARK_MAP_ID")
                    ?: readLocalProperty("services.maptiler.darkMapId")
            }
                ?: "basic-v2-dark"
        )
        // Geoapify - active runtime provider for both static map previews and
        // interactive tile-server style.json. Free tier (3000 requests/day)
        // covers chat-message previews and live-location maps for a single
        // user comfortably, unlike MapTiler whose free tier denies static
        // map rendering (HTTP 403 "Access to rendered maps not allowed").
        buildConfigFieldStr(
            name = "GEOAPIFY_STATIC_BASE_URL",
            value = "https://api.geoapify.com/v1/staticmap"
        )
        buildConfigFieldStr(
            name = "GEOAPIFY_STYLE_BASE_URL",
            value = "https://maps.geoapify.com/v1/styles"
        )
        buildConfigFieldStr(
            name = "GEOAPIFY_API_KEY",
            value = System.getenv("ELEMENT_ANDROID_GEOAPIFY_API_KEY")
                ?: readLocalProperty("services.geoapify.apikey")
                ?: ""
        )
        buildConfigFieldStr(
            name = "GEOAPIFY_LIGHT_STYLE",
            value = System.getenv("ELEMENT_ANDROID_GEOAPIFY_LIGHT_STYLE")
                ?: readLocalProperty("services.geoapify.lightStyle")
                ?: "osm-bright"
        )
        buildConfigFieldStr(
            name = "GEOAPIFY_DARK_STYLE",
            value = System.getenv("ELEMENT_ANDROID_GEOAPIFY_DARK_STYLE")
                ?: readLocalProperty("services.geoapify.darkStyle")
                ?: "dark-matter"
        )
    }
}

dependencies {
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.core)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.matrixui)
    implementation(projects.libraries.uiStrings)
    implementation(libs.coil.compose)
    implementation(libs.datetime)

    testCommonDependencies(libs)
}
