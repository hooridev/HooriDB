package dev.hoori.db.core.driver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Maven repository over HTTPS, by default Maven Central. Plain HTTP is refused, and so is a
 * response the client cannot parse, with a {@link ProtocolException}.
 */
public final class MavenRepository implements Repository {

  public static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  // Bounds both the wait for a response's headers and any pause between bytes of its body.
  // shortcut: a download that keeps trickling has no overall time limit, only one per pause; the
  // user can cancel it. Add a deadline if slow mirrors become a problem.
  private static final Duration STALL_TIMEOUT = Duration.ofSeconds(30);

  private final URI base;
  private final HttpClient client;

  public MavenRepository(URI base) {
    // The URL is never part of a message: it may carry a secret.
    if (!"https".equals(base.getScheme()) || base.getRawAuthority() == null) {
      throw new IllegalArgumentException("A repository URL must be https with a host");
    }
    if (base.getRawUserInfo() != null) {
      throw new IllegalArgumentException("A repository URL must not carry a user or password");
    }
    if (base.getRawQuery() != null || base.getRawFragment() != null) {
      throw new IllegalArgumentException("A repository URL must not have a query or fragment");
    }
    this.base = base.getRawPath().endsWith("/") ? base : URI.create(base + "/");
    // NORMAL follows redirects, but never one from HTTPS to HTTP.
    this.client = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public static MavenRepository central() {
    return new MavenRepository(CENTRAL);
  }

  @Override
  public Response open(String path) throws IOException, InterruptedException {
    var response = send(path, request(path).GET(), HttpResponse.BodyHandlers.ofPublisher());
    var body = new Body();
    response.body().subscribe(body);
    if (response.statusCode() != 200) {
      body.close();
      throw failure(path, response.statusCode());
    }
    long length;
    try {
      length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
    } catch (NumberFormatException e) {
      length = -2;
    }
    if (length < -1) {
      body.close();
      throw new ProtocolException("The repository sent a bad length for " + path);
    }
    return new Response(body, length);
  }

  @Override
  public Optional<Instant> published(String path) throws IOException, InterruptedException {
    var response = send(
        path,
        request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()),
        HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 200) {
      throw failure(path, response.statusCode());
    }
    try {
      return response
          .headers()
          .firstValue("Last-Modified")
          .map(date ->
              ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  URI uri(String path) {
    var uri = base.resolve(path);
    if (!uri.toString().startsWith(base.toString())) {
      throw new IllegalArgumentException("Not a path under the repository: " + path);
    }
    return uri;
  }

  private <T> HttpResponse<T> send(
      String path, HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try {
      return client.send(request.build(), handler);
    } catch (IllegalArgumentException e) {
      // The request is well formed, so this is the client refusing the response, such as one
      // whose Content-Length is no number or does not fit a long.
      var malformed = new ProtocolException("The repository sent a malformed response for " + path);
      malformed.initCause(e);
      throw malformed;
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(uri(path)).timeout(STALL_TIMEOUT);
  }

  private static IOException failure(String path, int status) {
    return status == 404 || status == 410
        ? new NoSuchFileException(path)
        : new Repository.Refused("The repository answered " + status + " for " + path);
  }

  // The JDK's own stream of a body waits for each part without a limit, so a server that stops
  // sending would hold a download forever. This one gives up after the stall timeout.
  private static final class Body extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {
    private static final Object END = new Object();

    private final BlockingQueue<Object> parts = new LinkedBlockingQueue<>();
    private volatile Flow.Subscription subscription;
    private volatile boolean closed;
    private Iterator<ByteBuffer> buffers = Collections.emptyIterator();
    private ByteBuffer buffer;
    private boolean ended;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (closed) {
        subscription.cancel();
      } else {
        subscription.request(1);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      parts.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      parts.add(throwable);
    }

    @Override
    public void onComplete() {
      parts.add(END);
    }

    @Override
    public int read() throws IOException {
      var one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      while (buffer == null || !buffer.hasRemaining()) {
        if (buffers.hasNext()) {
          buffer = buffers.next();
          continue;
        }
        if (ended || closed) {
          return -1;
        }
        Object part;
        try {
          part = parts.poll(STALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
        if (part == null) {
          throw new HttpTimeoutException("The download stalled");
        } else if (part == END) {
          ended = true;
        } else if (part instanceof Throwable t) {
          throw t instanceof IOException io ? io : new IOException(t);
        } else {
          buffers = ((List<ByteBuffer>) part).iterator();
          subscription.request(1);
        }
      }
      int n = Math.min(length, buffer.remaining());
      buffer.get(bytes, offset, n);
      return n;
    }

    @Override
    public void close() {
      closed = true;
      var s = subscription;
      if (s != null) {
        s.cancel();
      }
    }
  }
}
