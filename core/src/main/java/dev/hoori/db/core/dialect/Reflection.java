package dev.hoori.db.core.dialect;

// Calls a driver's own public methods, which the class loader of the drivers keeps out of reach.
final class Reflection {

  private Reflection() {}

  static Object call(Object target, String method) throws ReflectiveOperationException {
    return target.getClass().getMethod(method).invoke(target);
  }
}
