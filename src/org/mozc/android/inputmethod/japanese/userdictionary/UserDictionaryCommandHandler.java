/*
 * Copyright (C) 2026 The 2by2 Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.mozc.android.inputmethod.japanese.userdictionary;

import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;

import org.mozc.android.inputmethod.japanese.MozcLog;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryCommand.UserDictionaryCommand;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryCommand.UserDictionaryCommandStatus;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryCommand.UserDictionaryCommandStatus.Status;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionary;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionary.Entry;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionary.PosType;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side implementation of {@link UserDictionaryCommand}.
 *
 * <p>Older Mozc engines edited user dictionaries through SEND_USER_DICTIONARY_COMMAND. That
 * command was removed upstream; the dictionary is now a serialized {@link UserDictionaryStorage}
 * in {@code user_dictionary.db} of the user profile directory, edited by the client and picked
 * up by the engine on RELOAD (see SessionExecutor#sendUserDictionaryCommand). This class ports
 * the behavior of the former engine-side session (dictionary/user_dictionary_session*.cc,
 * user_dictionary_util.cc and user_dictionary_importer.cc of Mozc) to Java, so that the
 * existing user dictionary tool keeps working unchanged.
 */
public class UserDictionaryCommandHandler {

  static final String FILE_NAME = "user_dictionary.db";
  static final int MAX_DICTIONARY_SIZE = 100;
  static final int MAX_ENTRY_SIZE = 1000000;
  static final int MAX_STRING_SIZE = 300;  // in UTF-8 bytes
  static final long MAX_FILE_SIZE = 512L << 20;
  static final int MAX_UNDO_HISTORY = 30;
  static final String DEFAULT_DICTIONARY_NAME = "user dictionary";

  private final File file;
  private final SecureRandom random = new SecureRandom();

  private long sessionId = 0;
  private UserDictionaryStorage storage = UserDictionaryStorage.getDefaultInstance();
  private String defaultDictionaryName = DEFAULT_DICTIONARY_NAME;
  private final Deque<UserDictionaryStorage> undoHistory = new ArrayDeque<UserDictionaryStorage>();

  public UserDictionaryCommandHandler(File userProfileDirectory) {
    this.file = new File(Preconditions.checkNotNull(userProfileDirectory), FILE_NAME);
  }

  /** Evaluates {@code command}. */
  public synchronized UserDictionaryCommandStatus evaluate(UserDictionaryCommand command) {
    Preconditions.checkNotNull(command);
    UserDictionaryCommandStatus.Builder status = UserDictionaryCommandStatus.newBuilder();
    switch (command.getType()) {
      case NO_OPERATION:
        status.setStatus(checkSession(command));
        break;
      case CLEAR_STORAGE:
        status.setStatus(clearStorage());
        break;
      case CREATE_SESSION:
        createSession(status);
        break;
      case DELETE_SESSION:
        status.setStatus(deleteSession(command));
        break;
      default:
        Status sessionStatus = checkSession(command);
        if (sessionStatus != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
          status.setStatus(sessionStatus);
        } else {
          evaluateInSession(command, status);
        }
        break;
    }
    return status.build();
  }

  private void evaluateInSession(
      UserDictionaryCommand command, UserDictionaryCommandStatus.Builder status) {
    switch (command.getType()) {
      case SET_DEFAULT_DICTIONARY_NAME:
        status.setStatus(setDefaultDictionaryName(command));
        break;
      case CHECK_UNDOABILITY:
        status.setStatus(undoHistory.isEmpty()
            ? Status.NO_UNDO_HISTORY : Status.USER_DICTIONARY_COMMAND_SUCCESS);
        break;
      case UNDO:
        if (undoHistory.isEmpty()) {
          status.setStatus(Status.NO_UNDO_HISTORY);
        } else {
          storage = undoHistory.pop();
          status.setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
        }
        break;
      case LOAD:
        status.setStatus(load(command.getEnsureNonEmptyStorage()));
        break;
      case SAVE:
        status.setStatus(save());
        break;
      case GET_USER_DICTIONARY_NAME_LIST: {
        UserDictionaryStorage.Builder names = UserDictionaryStorage.newBuilder();
        for (UserDictionary dictionary : storage.getDictionariesList()) {
          names.addDictionaries(UserDictionary.newBuilder()
              .setId(dictionary.getId()).setName(dictionary.getName()));
        }
        status.setStorage(names).setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
        break;
      }
      case GET_ENTRY_SIZE: {
        int index = indexOf(command.getDictionaryId());
        if (index < 0) {
          status.setStatus(Status.UNKNOWN_DICTIONARY_ID);
        } else {
          status.setEntrySize(storage.getDictionaries(index).getEntriesCount())
              .setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
        }
        break;
      }
      case GET_ENTRIES:
        getEntries(command, status);
        break;
      case CHECK_NEW_DICTIONARY_AVAILABILITY:
        status.setStatus(storage.getDictionariesCount() >= MAX_DICTIONARY_SIZE
            ? Status.DICTIONARY_SIZE_LIMIT_EXCEEDED : Status.USER_DICTIONARY_COMMAND_SUCCESS);
        break;
      case CREATE_DICTIONARY:
        createDictionary(command.getDictionaryName(), status);
        break;
      case DELETE_DICTIONARY:
        status.setStatus(
            deleteDictionary(command.getDictionaryId(), command.getEnsureNonEmptyStorage()));
        break;
      case RENAME_DICTIONARY:
        status.setStatus(renameDictionary(command.getDictionaryId(), command.getDictionaryName()));
        break;
      case CHECK_NEW_ENTRY_AVAILABILITY: {
        int index = indexOf(command.getDictionaryId());
        if (index < 0) {
          status.setStatus(Status.UNKNOWN_DICTIONARY_ID);
        } else {
          status.setStatus(storage.getDictionaries(index).getEntriesCount() >= MAX_ENTRY_SIZE
              ? Status.ENTRY_SIZE_LIMIT_EXCEEDED : Status.USER_DICTIONARY_COMMAND_SUCCESS);
        }
        break;
      }
      case ADD_ENTRY:
        status.setStatus(addEntry(command.getDictionaryId(), command.getEntry()));
        break;
      case EDIT_ENTRY:
        status.setStatus(editEntry(command));
        break;
      case DELETE_ENTRY:
        status.setStatus(deleteEntries(command));
        break;
      case IMPORT_DATA:
        importData(command, status);
        break;
      case GET_STORAGE:
        status.setStorage(storage).setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
        break;
      default:
        status.setStatus(Status.UNKNOWN_COMMAND);
        break;
    }
  }

  // ---- Session ---------------------------------------------------------------------------

  private void createSession(UserDictionaryCommandStatus.Builder status) {
    // Only one session at a time, like the former engine: a new session replaces the old one.
    sessionId = newId();
    storage = UserDictionaryStorage.getDefaultInstance();
    defaultDictionaryName = DEFAULT_DICTIONARY_NAME;
    undoHistory.clear();
    status.setSessionId(sessionId).setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
  }

  private Status deleteSession(UserDictionaryCommand command) {
    Status result = checkSession(command);
    if (result == Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      sessionId = 0;
      storage = UserDictionaryStorage.getDefaultInstance();
      undoHistory.clear();
    }
    return result;
  }

  private Status checkSession(UserDictionaryCommand command) {
    if (sessionId == 0 || !command.hasSessionId() || command.getSessionId() != sessionId) {
      return Status.UNKNOWN_SESSION_ID;
    }
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status setDefaultDictionaryName(UserDictionaryCommand command) {
    Status result = validateDictionaryName(command.getDictionaryName());
    if (result == Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      defaultDictionaryName = command.getDictionaryName();
    }
    return result;
  }

  // ---- Storage ---------------------------------------------------------------------------

  private Status load(boolean ensureNonEmptyStorage) {
    Status result;
    if (!file.exists()) {
      storage = UserDictionaryStorage.getDefaultInstance();
      result = Status.FILE_NOT_FOUND;
    } else if (file.length() > MAX_FILE_SIZE) {
      result = Status.FILE_SIZE_LIMIT_EXCEEDED;
    } else {
      InputStream stream = null;
      try {
        stream = new FileInputStream(file);
        storage = UserDictionaryStorage.parseFrom(stream);
        result = Status.USER_DICTIONARY_COMMAND_SUCCESS;
      } catch (InvalidProtocolBufferException e) {
        MozcLog.e("Broken user dictionary: " + e.getMessage());
        result = Status.INVALID_FILE_FORMAT;
      } catch (IOException e) {
        MozcLog.e("Failed to read user dictionary: " + e.getMessage());
        result = Status.UNKNOWN_ERROR;
      } finally {
        closeQuietly(stream);
      }
    }
    boolean ensured = ensureNonEmptyStorage && ensureNonEmptyStorage();
    if (ensured || result == Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      undoHistory.clear();
    }
    return result;
  }

  /** Adds an empty default dictionary if there is none. Returns true if one was added. */
  private boolean ensureNonEmptyStorage() {
    if (storage.getDictionariesCount() > 0) {
      return false;
    }
    storage = storage.toBuilder()
        .addDictionaries(UserDictionary.newBuilder().setId(newId()).setName(defaultDictionaryName))
        .build();
    return true;
  }

  private Status save() {
    byte[] data = storage.toByteArray();
    if (data.length > MAX_FILE_SIZE) {
      return Status.FILE_SIZE_LIMIT_EXCEEDED;
    }
    // Write to a temporary file, then rename it over the dictionary.
    File temp = new File(file.getPath() + ".tmp");
    FileOutputStream stream = null;
    try {
      stream = new FileOutputStream(temp);
      stream.write(data);
      stream.getFD().sync();
    } catch (IOException e) {
      MozcLog.e("Failed to write user dictionary: " + e.getMessage());
      return Status.UNKNOWN_ERROR;
    } finally {
      closeQuietly(stream);
    }
    if (!temp.renameTo(file)) {
      MozcLog.e("Failed to rename " + temp + " to " + file);
      return Status.UNKNOWN_ERROR;
    }
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status clearStorage() {
    storage = UserDictionaryStorage.getDefaultInstance();
    undoHistory.clear();
    return save();
  }

  // ---- Dictionaries ----------------------------------------------------------------------

  private void createDictionary(String name, UserDictionaryCommandStatus.Builder status) {
    Status result = checkNewDictionary(name);
    if (result == Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      long id = newId();
      pushUndo();
      storage = storage.toBuilder()
          .addDictionaries(UserDictionary.newBuilder().setId(id).setName(name))
          .build();
      status.setDictionaryId(id);
    }
    status.setStatus(result);
  }

  private Status checkNewDictionary(String name) {
    if (storage.getDictionariesCount() >= MAX_DICTIONARY_SIZE) {
      return Status.DICTIONARY_SIZE_LIMIT_EXCEEDED;
    }
    Status result = validateDictionaryName(name);
    if (result != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      return result;
    }
    for (UserDictionary dictionary : storage.getDictionariesList()) {
      if (dictionary.getName().equals(name)) {
        return Status.DICTIONARY_NAME_DUPLICATED;
      }
    }
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status deleteDictionary(long dictionaryId, boolean ensureNonEmptyStorage) {
    int index = indexOf(dictionaryId);
    if (index < 0) {
      return Status.UNKNOWN_DICTIONARY_ID;
    }
    pushUndo();
    UserDictionaryStorage.Builder builder = storage.toBuilder().removeDictionaries(index);
    if (ensureNonEmptyStorage && builder.getDictionariesCount() == 0) {
      builder.addDictionaries(
          UserDictionary.newBuilder().setId(newId()).setName(defaultDictionaryName));
    }
    storage = builder.build();
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status renameDictionary(long dictionaryId, String name) {
    int index = indexOf(dictionaryId);
    if (index < 0) {
      return Status.UNKNOWN_DICTIONARY_ID;
    }
    if (storage.getDictionaries(index).getName().equals(name)) {
      return Status.USER_DICTIONARY_COMMAND_SUCCESS;  // Nothing to do.
    }
    Status result = validateDictionaryName(name);
    if (result != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      return result;
    }
    for (UserDictionary dictionary : storage.getDictionariesList()) {
      if (dictionary.getName().equals(name)) {
        return Status.DICTIONARY_NAME_DUPLICATED;
      }
    }
    pushUndo();
    storage = storage.toBuilder()
        .setDictionaries(index, storage.getDictionaries(index).toBuilder().setName(name))
        .build();
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  // ---- Entries ---------------------------------------------------------------------------

  private void getEntries(UserDictionaryCommand command, UserDictionaryCommandStatus.Builder status) {
    int index = indexOf(command.getDictionaryId());
    if (index < 0) {
      status.setStatus(Status.UNKNOWN_DICTIONARY_ID);
      return;
    }
    UserDictionary dictionary = storage.getDictionaries(index);
    for (int entryIndex : command.getEntryIndexList()) {
      if (entryIndex < 0 || entryIndex >= dictionary.getEntriesCount()) {
        status.clearEntries();
        status.setStatus(Status.ENTRY_INDEX_OUT_OF_RANGE);
        return;
      }
      status.addEntries(dictionary.getEntries(entryIndex));
    }
    status.setStatus(Status.USER_DICTIONARY_COMMAND_SUCCESS);
  }

  private Status addEntry(long dictionaryId, Entry entry) {
    int index = indexOf(dictionaryId);
    if (index < 0) {
      return Status.UNKNOWN_DICTIONARY_ID;
    }
    UserDictionary dictionary = storage.getDictionaries(index);
    if (dictionary.getEntriesCount() >= MAX_ENTRY_SIZE) {
      return Status.ENTRY_SIZE_LIMIT_EXCEEDED;
    }
    Status result = validateEntry(entry);
    if (result != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      return result;
    }
    pushUndo();
    storage = storage.toBuilder()
        .setDictionaries(index, dictionary.toBuilder().addEntries(entry))
        .build();
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status editEntry(UserDictionaryCommand command) {
    int index = indexOf(command.getDictionaryId());
    if (index < 0) {
      return Status.UNKNOWN_DICTIONARY_ID;
    }
    if (command.getEntryIndexCount() != 1) {
      return Status.INVALID_ARGUMENT;
    }
    UserDictionary dictionary = storage.getDictionaries(index);
    int entryIndex = command.getEntryIndex(0);
    if (entryIndex < 0 || entryIndex >= dictionary.getEntriesCount()) {
      return Status.ENTRY_INDEX_OUT_OF_RANGE;
    }
    Status result = validateEntry(command.getEntry());
    if (result != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
      return result;
    }
    pushUndo();
    storage = storage.toBuilder()
        .setDictionaries(index, dictionary.toBuilder().setEntries(entryIndex, command.getEntry()))
        .build();
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private Status deleteEntries(UserDictionaryCommand command) {
    int index = indexOf(command.getDictionaryId());
    if (index < 0) {
      return Status.UNKNOWN_DICTIONARY_ID;
    }
    UserDictionary dictionary = storage.getDictionaries(index);
    Set<Integer> toDelete = new HashSet<Integer>();
    for (int entryIndex : command.getEntryIndexList()) {
      if (entryIndex < 0 || entryIndex >= dictionary.getEntriesCount()) {
        return Status.ENTRY_INDEX_OUT_OF_RANGE;
      }
      toDelete.add(entryIndex);
    }
    UserDictionary.Builder builder = dictionary.toBuilder().clearEntries();
    for (int i = 0; i < dictionary.getEntriesCount(); ++i) {
      if (!toDelete.contains(i)) {
        builder.addEntries(dictionary.getEntries(i));
      }
    }
    pushUndo();
    storage = storage.toBuilder().setDictionaries(index, builder).build();
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  // ---- Import ----------------------------------------------------------------------------

  private void importData(UserDictionaryCommand command, UserDictionaryCommandStatus.Builder status) {
    UserDictionaryStorage before = storage;
    int index;
    if (command.hasDictionaryId()) {
      index = indexOf(command.getDictionaryId());
      if (index < 0) {
        status.setStatus(Status.UNKNOWN_DICTIONARY_ID);
        return;
      }
    } else {
      Status result = checkNewDictionary(command.getDictionaryName());
      if (result != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
        status.setStatus(result);
        return;
      }
      storage = storage.toBuilder()
          .addDictionaries(UserDictionary.newBuilder()
              .setId(newId()).setName(command.getDictionaryName()))
          .build();
      index = storage.getDictionariesCount() - 1;
    }

    UserDictionary dictionary = storage.getDictionaries(index);
    UserDictionary.Builder builder = dictionary.toBuilder();
    Set<String> existing = new HashSet<String>();
    for (Entry entry : dictionary.getEntriesList()) {
      existing.add(entryKey(entry));
    }
    boolean hasInvalidEntries = false;
    boolean tooManyWords = false;
    for (String line : command.getData().split("\r\n|\r|\n")) {
      Entry entry = parseLine(line);
      if (entry == null) {
        continue;  // Comment, header or empty line.
      }
      if (validateEntry(entry) != Status.USER_DICTIONARY_COMMAND_SUCCESS) {
        hasInvalidEntries = true;
        continue;
      }
      if (!existing.add(entryKey(entry))) {
        continue;  // Duplicated entry.
      }
      if (builder.getEntriesCount() >= MAX_ENTRY_SIZE) {
        tooManyWords = true;
        break;
      }
      builder.addEntries(entry);
    }
    storage = storage.toBuilder().setDictionaries(index, builder).build();
    pushUndo(before);
    status.setDictionaryId(storage.getDictionaries(index).getId());
    status.setStatus(tooManyWords ? Status.IMPORT_TOO_MANY_WORDS
        : hasInvalidEntries ? Status.IMPORT_INVALID_ENTRIES
        : Status.USER_DICTIONARY_COMMAND_SUCCESS);
  }

  private static String entryKey(Entry entry) {
    return entry.getKey() + '\t' + entry.getValue() + '\t' + entry.getPos().getNumber();
  }

  /**
   * Parses a line of an exported dictionary. Supports the tab separated format of Mozc,
   * MS-IME and ATOK ("reading TAB word TAB part of speech [TAB comment]") and the CSV format of
   * Kotoeri ("reading","word","part of speech"). Returns null for lines to be skipped.
   */
  static Entry parseLine(String line) {
    if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) {
      return null;
    }
    String[] fields;
    if (line.indexOf('\t') >= 0) {
      fields = line.split("\t", -1);
    } else if (line.startsWith("\"")) {
      fields = line.split(",", -1);
      for (int i = 0; i < fields.length; ++i) {
        String field = fields[i].trim();
        if (field.length() >= 2 && field.startsWith("\"") && field.endsWith("\"")) {
          field = field.substring(1, field.length() - 1);
        }
        fields[i] = field;
      }
    } else {
      return null;
    }
    if (fields.length < 3) {
      return null;
    }
    Entry.Builder entry = Entry.newBuilder()
        .setKey(fields[0])
        .setValue(fields[1])
        .setPos(toPosType(fields[2]));
    if (fields.length >= 4) {
      entry.setComment(fields[3]);
    }
    return entry.build();
  }

  private static final Map<String, PosType> POS_NAME_MAP = new HashMap<String, PosType>();
  static {
    String[][] names = {
        {"名詞", "NOUN"}, {"短縮よみ", "ABBREVIATION"}, {"サジェストのみ", "SUGGESTION_ONLY"},
        {"固有名詞", "PROPER_NOUN"}, {"人名", "PERSONAL_NAME"}, {"姓", "FAMILY_NAME"},
        {"名", "FIRST_NAME"}, {"組織", "ORGANIZATION_NAME"}, {"地名", "PLACE_NAME"},
        {"名詞サ変", "SA_IRREGULAR_CONJUGATION_NOUN"}, {"名詞形動", "ADJECTIVE_VERBAL_NOUN"},
        {"数", "NUMBER"}, {"数字", "NUMBER"}, {"アルファベット", "ALPHABET"}, {"記号", "SYMBOL"},
        {"顔文字", "EMOTICON"}, {"副詞", "ADVERB"}, {"連体詞", "PRENOUN_ADJECTIVAL"},
        {"接続詞", "CONJUNCTION"}, {"感動詞", "INTERJECTION"}, {"接頭語", "PREFIX"},
        {"助数詞", "COUNTER_SUFFIX"}, {"接尾一般", "GENERIC_SUFFIX"},
        {"接尾人名", "PERSON_NAME_SUFFIX"}, {"接尾地名", "PLACE_NAME_SUFFIX"},
        {"動詞ワ行五段", "WA_GROUP1_VERB"}, {"動詞カ行五段", "KA_GROUP1_VERB"},
        {"動詞サ行五段", "SA_GROUP1_VERB"}, {"動詞タ行五段", "TA_GROUP1_VERB"},
        {"動詞ナ行五段", "NA_GROUP1_VERB"}, {"動詞マ行五段", "MA_GROUP1_VERB"},
        {"動詞ラ行五段", "RA_GROUP1_VERB"}, {"動詞ガ行五段", "GA_GROUP1_VERB"},
        {"動詞バ行五段", "BA_GROUP1_VERB"}, {"動詞ハ行四段", "HA_GROUP1_VERB"},
        {"動詞一段", "GROUP2_VERB"}, {"動詞カ変", "KURU_GROUP3_VERB"},
        {"動詞サ変", "SURU_GROUP3_VERB"}, {"動詞ザ変", "ZURU_GROUP3_VERB"},
        {"動詞ラ変", "RU_GROUP3_VERB"}, {"形容詞", "ADJECTIVE"},
        {"終助詞", "SENTENCE_ENDING_PARTICLE"}, {"句読点", "PUNCTUATION"},
        {"独立語", "FREE_STANDING_WORD"}, {"抑制単語", "SUPPRESSION_WORD"},
        // Common names used by other IMEs.
        {"さ変名詞", "SA_IRREGULAR_CONJUGATION_NOUN"}, {"形動名詞", "ADJECTIVE_VERBAL_NOUN"},
        {"短縮読み", "ABBREVIATION"}, {"顔文字", "EMOTICON"}, {"品詞なし", "NOUN"},
    };
    for (String[] pair : names) {
      POS_NAME_MAP.put(pair[0], PosType.valueOf(pair[1]));
    }
  }

  static PosType toPosType(String name) {
    PosType type = POS_NAME_MAP.get(name.trim());
    return type == null ? PosType.NOUN : type;
  }

  // ---- Validation (from Mozc's dictionary/user_dictionary_util.cc) -----------------------

  static Status validateEntry(Entry entry) {
    String reading = entry.getKey();
    if (reading.isEmpty()) {
      return Status.READING_EMPTY;
    }
    if (isTooLong(reading)) {
      return Status.READING_TOO_LONG;
    }
    if (containsInvalidChars(reading)) {
      return Status.READING_CONTAINS_INVALID_CHARACTER;
    }
    String word = entry.getValue();
    if (word.isEmpty()) {
      return Status.WORD_EMPTY;
    }
    if (isTooLong(word)) {
      return Status.WORD_TOO_LONG;
    }
    if (containsInvalidChars(word)) {
      return Status.WORD_CONTAINS_INVALID_CHARACTER;
    }
    String comment = entry.getComment();
    if (isTooLong(comment)) {
      return Status.COMMENT_TOO_LONG;
    }
    if (containsInvalidChars(comment)) {
      return Status.COMMENT_CONTAINS_INVALID_CHARACTER;
    }
    if (!entry.hasPos() || entry.getPos() == PosType.NO_POS) {
      return Status.INVALID_POS_TYPE;
    }
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  static Status validateDictionaryName(String name) {
    if (name.isEmpty()) {
      return Status.DICTIONARY_NAME_EMPTY;
    }
    if (isTooLong(name)) {
      return Status.DICTIONARY_NAME_TOO_LONG;
    }
    if (containsInvalidChars(name)) {
      return Status.DICTIONARY_NAME_CONTAINS_INVALID_CHARACTER;
    }
    return Status.USER_DICTIONARY_COMMAND_SUCCESS;
  }

  private static boolean isTooLong(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STRING_SIZE;
  }

  private static boolean containsInvalidChars(String s) {
    return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0;
  }

  // ---- Helpers ---------------------------------------------------------------------------

  private int indexOf(long dictionaryId) {
    List<UserDictionary> dictionaries = storage.getDictionariesList();
    for (int i = 0; i < dictionaries.size(); ++i) {
      if (dictionaries.get(i).getId() == dictionaryId) {
        return i;
      }
    }
    return -1;
  }

  private long newId() {
    List<Long> used = new ArrayList<Long>();
    for (UserDictionary dictionary : storage.getDictionariesList()) {
      used.add(dictionary.getId());
    }
    long id;
    do {
      id = random.nextLong();
    } while (id == 0 || used.contains(id));
    return id;
  }

  private void pushUndo() {
    pushUndo(storage);
  }

  private void pushUndo(UserDictionaryStorage snapshot) {
    undoHistory.push(snapshot);
    while (undoHistory.size() > MAX_UNDO_HISTORY) {
      undoHistory.removeLast();
    }
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        // Ignore.
      }
    }
  }
}
