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
 * limitations under the License.
 */

package com.android.settings.applications.specialaccess;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import com.android.settings.testutils.SettingsRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivityManager;

@RunWith(SettingsRobolectricTestRunner.class)
public class EnabledVrListenersControllerTest {

    private Context mContext;
    private EnabledVrListenersController mController;
    private ShadowActivityManager mActivityManager;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mController = new EnabledVrListenersController(mContext, "key");
        mActivityManager = Shadow.extract(mContext.getSystemService(Context.ACTIVITY_SERVICE));
    }

    @Test
    public void isAvailable_byDefault_true() {
        assertThat(mController.isAvailable()).isTrue();
    }

    @Test
    public void isAvailable_lowMemory_false() {
        mActivityManager.setIsLowRamDevice(true);
        assertThat(mController.isAvailable()).isTrue();
    }

    @Test
    @Config(qualifiers = "mcc999")
    public void isAvailable_disabled_false() {
        assertThat(mController.isAvailable()).isFalse();
    }
}