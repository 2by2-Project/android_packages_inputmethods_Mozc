/*
 * Copyright (C) 2026 The 2by2 Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * JNI binding of libmozc.so.
 *
 * <p>Upstream Mozc's android/jni/mozcjni.cc exports only
 * {@code Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJni_initialize}, which
 * registers the other native methods of this class. Hence the class has to live in this
 * package with exactly these method names and signatures.
 */
public final class MozcJni {

  private MozcJni() {}

  /** Registers the native methods below. Must be called right after loading the library. */
  public static native boolean initialize();

  /** Evaluates a serialized {@code mozc.commands.Command} and returns the result. */
  public static native byte[] evalCommand(byte[] command);

  /**
   * Initializes the engine.
   *
   * @param userProfileDirectoryPath directory to store user data (history, dictionary, ...)
   * @param dataFilePath path to mozc.data, or {@code null} for the minimal engine
   */
  public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);

  /** Returns the version of the loaded data set, or an empty string before initialization. */
  public static native String getDataVersion();
}
