/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import java.io.File

/**
 * Are we building with the phone-layer sources?
 *
 * Mirrors the upstream Enterprise toggle: presence of phone-layer/README.md
 * activates the layer. A vanilla upstream checkout has no phone-layer
 * directory and therefore builds exactly as Element X always has.
 */
val isPhoneLayerBuild: Boolean = File("phone-layer/README.md").exists()
