package dev.hoori.db.core.document;

/**
 * A driver of a server of documents, as a {@link dev.hoori.db.core.driver.LoadedDriver} holds it:
 * a class of core's that is defined in the class loader of the downloaded driver, so that it sees
 * the driver's classes and those of core, and speaks to core in core's values alone.
 */
public interface DocumentDriver {

  /**
   * A client of the target's server. It may not have reached the server yet: the first command
   * does, within the target's time limit.
   *
   * @param password the password, wiped by the caller once this returns; empty for none
   */
  DocumentClient connect(Target target, char[] password) throws DocumentException;
}
