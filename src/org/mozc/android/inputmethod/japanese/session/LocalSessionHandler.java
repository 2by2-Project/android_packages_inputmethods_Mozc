// Copyright 2010-2018, Google Inc.
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package org.mozc.android.inputmethod.japanese.session;

import org.mozc.android.inputmethod.japanese.MozcLog;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command;
import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Concrete SessionHandler. Calls JNI.
 *
 */
public class LocalSessionHandler implements SessionHandler {

  private static final String USER_PROFILE_DIRECTORY_NAME = ".mozc";

  @Override
  public void initialize(Context context) {
    File userProfileDirectory = getUserProfileDirectory(context);
    // Load the shared object with the data set bundled in the APK (standalone builds) or the
    // system data set installed by the build.
    MozcJNI.load(userProfileDirectory.getAbsolutePath(), findDataFile(context));
  }

  /**
   * Returns the directory holding the user's data (history, user dictionary, ...), creating it
   * if necessary.
   */
  public static File getUserProfileDirectory(Context context) {
    ApplicationInfo info = Preconditions.checkNotNull(context).getApplicationInfo();
    File userProfileDirectory = new File(info.dataDir, USER_PROFILE_DIRECTORY_NAME);
    if (!userProfileDirectory.exists() && !userProfileDirectory.mkdirs()) {
      // The engine still works without it, but no persistent data (e.g. user history, user
      // dictionary) will be stored.
      MozcLog.e(
          "Failed to create user profile directory: " + userProfileDirectory.getAbsolutePath());
    }
    return userProfileDirectory;
  }

  /** Asset name of the data set bundled in standalone (non-system) APK builds. */
  private static final String BUNDLED_DATA_ASSET_NAME = "mozc.data";

  /** Candidate locations of the OSS data set (see prebuilt_usr_share "mozc.data"). */
  private static final String[] DATA_FILE_PATHS = {
      "/product/usr/share/mozc/mozc.data",
      "/system_ext/usr/share/mozc/mozc.data",
      "/system/usr/share/mozc/mozc.data",
  };

  private static String findDataFile(Context context) {
    // A data set bundled in the APK always matches the libmozc.so of the same APK, so it takes
    // precedence. The system builds do not bundle it and fall through to the system paths.
    String bundled = extractBundledDataFile(context);
    if (bundled != null) {
      return bundled;
    }
    for (String path : DATA_FILE_PATHS) {
      if (new File(path).canRead()) {
        return path;
      }
    }
    MozcLog.e("mozc.data is not found. Falling back to the minimal engine.");
    return null;
  }

  /**
   * Copies the data set bundled as an asset to the app's private storage (the engine needs a
   * file path to mmap it) and returns its path, or {@code null} if no data set is bundled.
   * The copy is refreshed whenever the app is installed or updated.
   */
  private static String extractBundledDataFile(Context context) {
    File dataFile = new File(context.getFilesDir(), BUNDLED_DATA_ASSET_NAME);
    InputStream in;
    try {
      in = context.getAssets().open(BUNDLED_DATA_ASSET_NAME);
    } catch (IOException e) {
      // No data set is bundled in this build (the system build).
      return null;
    }
    try {
      long lastUpdateTime = 0;
      try {
        PackageInfo packageInfo =
            context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        lastUpdateTime = packageInfo.lastUpdateTime;
      } catch (PackageManager.NameNotFoundException e) {
        // Always copy.
      }
      if (dataFile.canRead() && dataFile.length() > 0 && lastUpdateTime != 0
          && dataFile.lastModified() >= lastUpdateTime) {
        return dataFile.getAbsolutePath();
      }
      File tmpFile = new File(context.getFilesDir(), BUNDLED_DATA_ASSET_NAME + ".tmp");
      try (OutputStream out = new FileOutputStream(tmpFile)) {
        byte[] buffer = new byte[64 * 1024];
        int length;
        while ((length = in.read(buffer)) > 0) {
          out.write(buffer, 0, length);
        }
      }
      if (!tmpFile.renameTo(dataFile)) {
        MozcLog.e("Failed to install the bundled mozc.data: " + dataFile.getAbsolutePath());
        return null;
      }
      return dataFile.getAbsolutePath();
    } catch (IOException e) {
      MozcLog.e("Failed to extract the bundled mozc.data", e);
      return null;
    } finally {
      try {
        in.close();
      } catch (IOException e) {
        // Ignore.
      }
    }
  }

  @Override
  public Command evalCommand(Command command) {
    byte[] inBytes = Preconditions.checkNotNull(command).toByteArray();
    byte[] outBytes = null;
    outBytes = MozcJNI.evalCommand(inBytes);
    try {
      return Command.parseFrom(outBytes);
    } catch (InvalidProtocolBufferException e) {
      MozcLog.w("InvalidProtocolBufferException is thrown."
          + "We can do nothing so just return default instance.");
      MozcLog.w(e.toString());
      return Command.getDefaultInstance();
    }
  }
}
