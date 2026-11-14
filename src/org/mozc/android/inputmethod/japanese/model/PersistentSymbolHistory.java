/*
 * Copyright (C) 2026 The 2by2 Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.mozc.android.inputmethod.japanese.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.common.base.Preconditions;

import org.json.JSONArray;
import org.json.JSONException;
import org.mozc.android.inputmethod.japanese.MozcLog;
import org.mozc.android.inputmethod.japanese.model.SymbolCandidateStorage.SymbolHistoryStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Symbol / emoticon / emoji history kept by the client.
 *
 * <p>The engine used to keep these histories in its "generic storage" (an LRU storage of 100
 * entries per category), which was removed from upstream Mozc. This class keeps the same
 * behavior in the application's shared preferences: the most recently used value comes first,
 * and each category holds at most {@link #MAX_SIZE} values.
 */
public class PersistentSymbolHistory implements SymbolHistoryStorage {

  static final int MAX_SIZE = 100;
  private static final String PREFERENCE_NAME = "symbol_history";

  private final SharedPreferences preferences;

  public PersistentSymbolHistory(Context context) {
    this.preferences = Preconditions.checkNotNull(context)
        .getApplicationContext()
        .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
  }

  private static String key(SymbolMajorCategory majorCategory) {
    return "history_" + majorCategory.name();
  }

  @Override
  public synchronized List<String> getAllHistory(SymbolMajorCategory majorCategory) {
    Preconditions.checkNotNull(majorCategory);
    String json = preferences.getString(key(majorCategory), null);
    if (json == null) {
      return Collections.emptyList();
    }
    try {
      JSONArray array = new JSONArray(json);
      List<String> result = new ArrayList<String>(array.length());
      for (int i = 0; i < array.length(); ++i) {
        result.add(array.getString(i));
      }
      return result;
    } catch (JSONException e) {
      MozcLog.w("Broken symbol history is discarded: " + e.getMessage());
      return Collections.emptyList();
    }
  }

  @Override
  public synchronized void addHistory(SymbolMajorCategory majorCategory, String value) {
    Preconditions.checkNotNull(majorCategory);
    Preconditions.checkNotNull(value);
    List<String> history = new ArrayList<String>(getAllHistory(majorCategory));
    history.remove(value);
    history.add(0, value);
    while (history.size() > MAX_SIZE) {
      history.remove(history.size() - 1);
    }
    preferences.edit().putString(key(majorCategory), new JSONArray(history).toString()).apply();
  }

  /** Clears the histories of all the categories. */
  public synchronized void clearAll() {
    SharedPreferences.Editor editor = preferences.edit();
    for (SymbolMajorCategory majorCategory : SymbolMajorCategory.values()) {
      editor.remove(key(majorCategory));
    }
    editor.apply();
  }
}
