/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialer.binary.aosp

import com.android.dialer.binary.common.DialerApplication
import com.android.dialer.inject.ContextModule
import dagger.hilt.android.HiltAndroidApp

/**
 * The application class for the AOSP Dialer. This is a version of the Dialer app that has no
 * dependency on Google Play Services.
 *
 * Hilt's SingletonComponent and the hand-written [AospDialerRootComponent] are two independent
 * graphs living side by side. Hilt is what new code binds into; the root component keeps serving
 * the upstream `Component.get(context)` call sites until they migrate.
 */
@HiltAndroidApp
class AospDialerApplication : DialerApplication() {
    /** Returns a new instance of the root component for the AOSP Dialer. */
    override fun buildRootComponent(): AospDialerRootComponent =
        AospDialerRootComponent.create(ContextModule(this))
}
