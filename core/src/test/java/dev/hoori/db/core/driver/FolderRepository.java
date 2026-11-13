package dev.hoori.db.core.driver;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** A repository in a folder of a test, which counts what is asked of it. */
final class FolderRepository implements Repository {

  final Path root;
  final List<String> opened = new ArrayList<>();
  final List<String> asked = new ArrayList<>();
  boolean offline;

  FolderRepository(Path root) {
    this.root = root;
  }

  @Override
  public Response open(String path) throws IOException {
    if (offline) {
      throw new ConnectException("offline");
    }
    opened.add(path);
    var file = root.resolve(path);
    return new Response(Files.newInputStream(file), Files.size(file));
  }

  @Override
  public Optional<Instant> published(String path) throws IOException {
    if (offline) {
      throw new ConnectException("offline");
    }
    asked.add(path);
    return Optional.of(Files.getLastModifiedTime(root.resolve(path)).toInstant());
  }

  enum Sum {
    SHA256,
    SHA1,
    NONE
  }

  /** Puts a version of the driver into the repository, with a checksum beside it. */
  Path publish(Driver driver, String version, byte[] jar, Sum sum, Instant published)
      throws Exception {
    var dir = root.resolve(driver.group().replace('.', '/'))
        .resolve(driver.artifact())
        .resolve(version);
    Files.createDirectories(dir);
    var file = dir.resolve(driver.artifact() + "-" + version + ".jar");
    Files.write(file, jar);
    Files.setLastModifiedTime(file, FileTime.from(published));
    switch (sum) {
      case SHA256 ->
        Files.writeString(dir.resolve(file.getFileName() + ".sha256"), hex("SHA-256", jar));
      case SHA1 ->
        Files.writeString(
            dir.resolve(file.getFileName() + ".sha1"),
            hex("SHA-1", jar) + "  " + file.getFileName());
      case NONE -> {}
    }
    var metadata = dir.getParent().resolve("maven-metadata.xml");
    var versions = new TreeSet<String>(Maven.NEWEST_FIRST);
    if (Files.exists(metadata)) {
      var text = Files.readString(metadata);
      for (var part : text.split("<version>")) {
        if (part.contains("</version>")) {
          versions.add(part.substring(0, part.indexOf("</version>")));
        }
      }
    }
    versions.add(version);
    var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata><groupId>")
        .append(driver.group())
        .append("</groupId><artifactId>")
        .append(driver.artifact())
        .append("</artifactId><versioning><versions>");
    versions.reversed().forEach(v -> xml.append("<version>").append(v).append("</version>"));
    xml.append("</versions></versioning></metadata>");
    Files.writeString(metadata, xml, StandardCharsets.UTF_8);
    return file;
  }

  static String hex(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }
}
