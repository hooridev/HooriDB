package dev.hoori.db.core.connection;

/**
 * What the app remembers of its layout on this machine, kept in {@link UiStateStore#FILE}: the
 * window's placement as opaque text from the UI framework, the sidebar's width, and the statement
 * log's open state, height and detail split. Each field is null where nothing is stored.
 */
public record UiState(
    String window, Float sidebar, Boolean logOpen, Float logHeight, Float logDetail) {

  public static final UiState EMPTY = new UiState(null, null, null, null, null);

  public UiState {
    if (sidebar != null && !validSize(sidebar)) {
      throw new IllegalArgumentException("sidebar must be finite and not negative");
    }
    if (logHeight != null && !validSize(logHeight)) {
      throw new IllegalArgumentException("logHeight must be finite and not negative");
    }
    if (logDetail != null && !validDetail(logDetail)) {
      throw new IllegalArgumentException("logDetail must be finite and between 0 and 1");
    }
  }

  static boolean validSize(float value) {
    return Float.isFinite(value) && value >= 0f;
  }

  static boolean validDetail(float value) {
    return Float.isFinite(value) && value >= 0f && value <= 1f;
  }

  public UiState withWindow(String window) {
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }

  public UiState withSidebar(Float sidebar) {
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }

  public UiState withLogOpen(Boolean logOpen) {
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }

  public UiState withLogHeight(Float logHeight) {
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }

  public UiState withLogDetail(Float logDetail) {
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }
}
