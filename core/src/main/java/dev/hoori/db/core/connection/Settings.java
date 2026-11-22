package dev.hoori.db.core.connection;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * The user's settings, kept in {@link SettingsStore#FILE}. Every field has a default in {@link
 * #DEFAULT}. The time zone is null for the computer's own.
 */
public record Settings(
    Look look,
    Color accent,
    Density density,
    boolean highlightOccurrences,
    KeywordCase keywordCase,
    boolean checkForUpdates,
    Duration connectTimeout,
    boolean newConnectionsReadOnly,
    CommitMode commitMode,
    ZoneId timeZone,
    CopyNull copyNull,
    DocumentView documentView,
    boolean keepHistory,
    int monitorInterval) {

  public static final int MIN_CONNECT_TIMEOUT_SECONDS = 1;
  public static final int MAX_CONNECT_TIMEOUT_SECONDS = 300;
  public static final List<Integer> MONITOR_INTERVALS = List.of(1, 2, 5, 10, 30);

  public static final Settings DEFAULT = new Settings(
      Look.SYSTEM,
      Color.BLUE,
      Density.DEFAULT,
      true,
      KeywordCase.UPPER,
      true,
      Duration.ofSeconds(10),
      false,
      CommitMode.AUTO,
      null,
      CopyNull.EMPTY,
      DocumentView.GRID,
      true,
      5);

  /** Which theme the windows wear: the operating system's, light or dark. */
  public enum Look {
    SYSTEM,
    LIGHT,
    DARK
  }

  /** How tightly rows sit in trees and grids. */
  public enum Density {
    COMPACT,
    DEFAULT,
    COMFORTABLE
  }

  /** How SQL keywords are written when a completion inserts one. */
  public enum KeywordCase {
    UPPER,
    LOWER
  }

  /** Whether a new SQL console commits each statement by itself or waits for Commit. */
  public enum CommitMode {
    AUTO,
    MANUAL
  }

  /** What copying a NULL cell puts on the clipboard. */
  public enum CopyNull {
    EMPTY,
    NULL,
    BACKSLASH_N
  }

  /** Which view a MongoDB collection or console result opens in first. */
  public enum DocumentView {
    GRID,
    JSON,
    TREE
  }

  public Settings {
    Objects.requireNonNull(look, "look");
    Objects.requireNonNull(accent, "accent");
    Objects.requireNonNull(density, "density");
    Objects.requireNonNull(keywordCase, "keywordCase");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(commitMode, "commitMode");
    Objects.requireNonNull(copyNull, "copyNull");
    Objects.requireNonNull(documentView, "documentView");
    if (!validConnectTimeout(connectTimeout)) {
      throw new IllegalArgumentException("connectTimeout must be whole seconds from "
          + MIN_CONNECT_TIMEOUT_SECONDS
          + " to "
          + MAX_CONNECT_TIMEOUT_SECONDS);
    }
    if (!validMonitorInterval(monitorInterval)) {
      throw new IllegalArgumentException("monitorInterval must be one of " + MONITOR_INTERVALS);
    }
  }

  static boolean validConnectTimeout(Duration timeout) {
    return timeout.getNano() == 0
        && timeout.getSeconds() >= MIN_CONNECT_TIMEOUT_SECONDS
        && timeout.getSeconds() <= MAX_CONNECT_TIMEOUT_SECONDS;
  }

  static boolean validMonitorInterval(int seconds) {
    return MONITOR_INTERVALS.contains(seconds);
  }

  public Settings withLook(Look look) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withAccent(Color accent) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withDensity(Density density) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withHighlightOccurrences(boolean highlightOccurrences) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withKeywordCase(KeywordCase keywordCase) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withCheckForUpdates(boolean checkForUpdates) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withConnectTimeout(Duration connectTimeout) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withNewConnectionsReadOnly(boolean newConnectionsReadOnly) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withCommitMode(CommitMode commitMode) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withTimeZone(ZoneId timeZone) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withCopyNull(CopyNull copyNull) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withDocumentView(DocumentView documentView) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withKeepHistory(boolean keepHistory) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }

  public Settings withMonitorInterval(int monitorInterval) {
    return new Settings(
        look,
        accent,
        density,
        highlightOccurrences,
        keywordCase,
        checkForUpdates,
        connectTimeout,
        newConnectionsReadOnly,
        commitMode,
        timeZone,
        copyNull,
        documentView,
        keepHistory,
        monitorInterval);
  }
}
